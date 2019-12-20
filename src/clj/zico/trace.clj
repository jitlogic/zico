(ns zico.trace
  (:require
    [clj-time.coerce :as ctc]
    [clj-time.format :as ctf]
    [ring.util.http-response :as rhr]
    [zico.util :as zu]
    [zico.elastic :as ze]
    [clojure.tools.logging :as log]
    [clojure.data.json :as json])
  (:import
    (com.jitlogic.zorka.common.util Base64 ZorkaRuntimeException)
    (com.jitlogic.zorka.common.collector TraceChunkData Collector)
    (com.jitlogic.zorka.common.tracedata HttpConstants)))


(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))

(defn parse-search-query [{:keys [fetch-attrs errors-only spans-only
                                  min-tstamp max-tstamp min-duration
                                  attr-matches text match-start match-end]}]
  :NIECZYNNE)

(defn from-chunk-metadata [tfn ^TraceChunkData c]
  (->
    (merge
      {:trace-id     (.getTraceIdHex c)
       :span-id      (.getSpanIdHex c)
       :parent-id    (.getParentIdHex c)
       :chunk-num    (.getChunkNum c)
       :tst          (.getTstamp c)
       :tstamp       (zu/str-time-yymmdd-hhmmss-sss (long (/ (.getTstamp c) 1000000)))
       :duration     (.getDuration c)
       :recs         (.getRecs c)
       :calls        (.getCalls c)
       :has-children (.isHasChildren c)
       :errors       (.getErrors c)
       }
      (when (.hasError c) {:error true})
      (when-let [attrs (.getAttrs c)]
        {:attrs (into {} (for [[k v] attrs] {(str k) (str v)}))})
      (when (> (.size (.getChildren c)) 0)
        (let [children (doall (for [c (.getChildren c)] (from-chunk-metadata tfn c)))]
          (when children {:children children}))))
    tfn))

(defn trace-get [{:keys [tstore trace-desc]} tid]
  (let [[t1 t2] (zu/parse-hex-tid tid)]
    (from-chunk-metadata trace-desc :NIECZYNNE)))

(defn trace-search [{:keys [tstore trace-desc]} query]
  (for [c :NIECZYNNE]
    (from-chunk-metadata trace-desc c)))


(def RE-METHOD-DESC #"(.*)\s+(.*)\.(.+)\.([^\(]+)(\(.*\))")

(defn parse-method-str [s]
  ; TODO dedicated StructuredTextIndex method that returns method description already parsed
  (when s
    (when-let [[_ r p c m a] (re-matches RE-METHOD-DESC s)]
      (let [cs (.split c "\\." 0), cl (alength cs)]
        {:result r, :package p, :class c, :method m, :args a}))))



(defn trace-detail [{:keys [tstore]} stack-limit tid sid]
  (let [[tid1 tid2] (zu/parse-hex-tid tid), [sid1 _] (zu/parse-hex-tid sid),
        rtr :NIECZYNNE
        rslt :NIECZYNNE]
    (merge {:trace-id tid :span-id sid} rslt)))


(defn trace-stats [{:keys [tstore]} tid sid]
  (let [[tid1 tid2] (zu/parse-hex-tid tid), [sid1 _] (zu/parse-hex-tid sid),
        ; TODO zaimplementować po stronie javy coś do wyciągania statsów
        rtr :NIECZYNNE]
    (vec
      (for [s (:NIECZYNNE :NIECZYNNE)]
        {:mid          (:mid s)
         :recs         (.getRecs s),
         :errors       (.getErrors s),
         :sum-duration (:maxduration s),
         :max-duration (:minDuration s),
         :min-duration (:minDuration),
         :method       (:method)}))))

(defn dump-trace-req [path uri session-id session-reset trace-id data]
  (let [headers (merge {"content-type"                     [HttpConstants/ZORKA_CBOR_CONTENT_TYPE]
                        HttpConstants/HDR_ZORKA_SESSION_ID [session-id]}
                       (when session-reset {HttpConstants/HDR_ZORKA_SESSION_RESET [session-reset]})
                       (when trace-id {HttpConstants/HDR_ZORKA_TRACE_ID [trace-id]}))]
    (locking path
      ; TODO use json/write, current implementation is inefficient
      (spit path (str (json/write-str {:uri uri, :headers headers, :body (Base64/encode data false)}) "\n") :append true))))

(defn submit-agd [{{{:keys [dump dump-path]} :log} :conf tstore :tstore} session-id session-reset data]
  (try
    (.handleAgentData (:collector @tstore) session-id session-reset data)
    (when dump (dump-trace-req dump-path "/agent/submit/agd" session-id session-reset nil data))
    (rhr/accepted)
    ; TODO session-renew - handle broken sessions
    ;(catch Exception _ (rhr/bad-request {:reason "Missing session UUID header."}))
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch Exception e
      (log/error e (str "Error processing AGD data: " (String. ^bytes data "UTF-8")))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn submit-trc [{{{:keys [dump dump-path]} :log} :conf :keys [tstore]} session-id trace-id chnum data]
  (try
    ; TODO weryfikacja argumentów
    (.handleTraceData ^Collector (:collector @tstore) session-id trace-id chnum data)
    (when dump (dump-trace-req dump-path "/agent/submit/trc" session-id nil trace-id data))
    (rhr/accepted)
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch ZorkaRuntimeException _
      (rhr/unauthorized {:reason "invalid or missing session ID header"}))
    (catch Exception e
      (log/error e "Error processing TRC data: " (Base64/encode data false))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn trace-desc-default [t]
  (or
    (get-in t [:attrs "call.method"])
    (str "TRACE@(" (:tstamp t) ")")))


(defn trace-desc-fn [{:keys [trace-types]}]
  (let [dfn (into {} (for [tt (vals trace-types)] {(:component tt) #(or (get-in % [:attrs (name (:render tt))]) (trace-desc-default %))}))]
    (fn [t]
      (let [c (get-in t [:attrs "component"])]
        (cond
          (nil? c) (trace-desc-default t)
          (contains? dfn c) ((dfn c) t)
          :else (trace-desc-default t)
          )))))


(defn with-tracer-components [{{new-conf :tstore} :conf :as app-state}
                              {{old-conf :tstore} :conf :keys [tstore]}]
  (let [new-state
        (if (:enabled new-conf true)
          (assoc app-state :tstore (ze/elastic-trace-store new-conf old-conf tstore))
          app-state)
        tfn (trace-desc-fn (:conf app-state))]
    (assoc new-state :trace-desc #(assoc % :desc (tfn %)))))

