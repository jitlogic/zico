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
    (com.jitlogic.zorka.common.collector TraceChunkData Collector TraceDataExtractor TraceDataResult TraceStatsExtractor TraceStatsResult)
    (com.jitlogic.zorka.common.tracedata HttpConstants)
    (java.util ArrayList Collection)))


(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))

(defn tcd->chunk [tfn ^TraceChunkData c]
  (->
    (merge
      {:traceid     (.getTraceIdHex c)
       :spanid      (.getSpanIdHex c)
       :parentid    (.getParentIdHex c)
       :chnum       (.getChunkNum c)
       :tst         (.getTstamp c)
       :tstamp      (zu/str-time-yymmdd-hhmmss-sss (long (/ (.getTstamp c) 1000000)))
       :duration    (.getDuration c)
       :klass       (.getKlass c)
       :method      (.getMethod c)
       :ttype        (.getTtype c)
       :recs         (.getRecs c)
       :calls        (.getCalls c)
       :errors       (.getErrors c)
       :has-children (.isHasChildren c)
       :tdata        (.getTraceData c)}
      (when (.hasError c) {:error true})
      (when-let [attrs (.getAttrs c)]
        {:attrs (into {} (for [[k v] attrs] {(str k) (str v)}))})
      (when (> (.size (.getChildren c)) 0)
        (let [children (doall (for [c (.getChildren c)] (tcd->chunk tfn c)))]
          (when children {:children children}))))
    tfn))

(defn chunk->tcd [{:keys [traceid spanid parentid chnum tst duration klass method ttype recs calls errors tdata]}]
  (let [tcd (TraceChunkData. traceid spanid parentid chnum)]
    (when tst (.setTstamp tcd tst))
    (when duration (.setDuration tcd duration))
    (when klass (.setKlass tcd klass))
    (when method (.setMethod tcd method))
    (when ttype (.setTtype tcd ttype))
    (when recs (.setRecs tcd (.intValue recs)))
    (when calls (.setCalls tcd (.intValue calls)))
    (when errors (.setErrors tcd (.intValue errors)))
    (.setTraceData tcd (zu/b64dec tdata))
    tcd))

(defn chunks->tree-node [node cgroups]
  (let [children (for [[k [v & _]] cgroups :when (= (:spanid node) (:parentid v))] v)]
    (if (empty? children) node (assoc node :children children))))

(defn chunks->tree [chunks]
  (let [cgroups (group-by #(:parentid % :root) chunks)]
    (when (:root cgroups)
      (chunks->tree-node (first (:root cgroups)) cgroups))))

(defn trace-search [{:keys [conf trace-desc]} query]
  (map trace-desc (ze/trace-search (:tstore conf) query)))

(def RE-METHOD-DESC #"(.*)\s+(.*)\.(.+)\.([^\(]+)(\(.*\))")

(defn parse-method-str [s]
  ; TODO dedicated StructuredTextIndex method that returns method description already parsed
  (when s
    (when-let [[_ r p c m a] (re-matches RE-METHOD-DESC s)]
      (let [cs (.split c "\\." 0), cl (alength cs)]
        {:result r, :package p, :class c, :method m, :args a}))))

(defn merge-chunk-data [chunks]
  (:tdata (first chunks)))                                  ; TODO

(defn tdr->tr [^TraceDataResult tdr]
  "Converts TraceDataRecord to clojure map matching TraceRecord schema."
  (merge
    {:method   (str (.getMethod tdr)),
     :pos      (.getChunkPos tdr),
     :errors   (.getErrors tdr),
     :duration (- (.getTstop tdr) (.getTstart tdr))
     :tstart   (.getTstart tdr)}
    (when-let [attrs (.getAttributes tdr)]
      {:attrs (into {} attrs)})
    (when-let [children (.getChildren tdr)]
      (map tdr->tr children)))
  )

(defn trace-detail [{:keys [conf tstore]} traceid spanid]
  (let [chunks (ze/trace-search (:tstore conf) {:traceid traceid :spanid spanid} :chunks? true)
        tex (TraceDataExtractor. (:resolver @tstore))
        rslt (.extract tex (ArrayList. ^Collection (map chunk->tcd chunks)))]
    (tdr->tr rslt)))

(defn tsr->ts [^TraceStatsResult tsr]
  "Converts TraceStatsResult to clojure map matching TraceStats schema"
  {:mid (.getMid tsr)
   :recs (.getRecs tsr)
   :errors (.getErrors tsr)
   :sum-duration (.getSumDuration tsr)
   :max-duration (.getMaxDuration tsr)
   :method (.getMethod tsr)})

(defn trace-stats [{:keys [conf]} traceid spanid]
  (let [chunks (ze/trace-search (:tstore conf) {:traceid traceid :spanid spanid} :chunks? true)
        tex (TraceStatsExtractor.)
        rslt (.extract tex (ArrayList. ^Collection (map chunk->tcd chunks)))]
    (vec (map tsr->ts rslt))))

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
    (catch Exception e
      (log/error e (str "Error processing AGD data: " (String. ^bytes data "UTF-8")))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn submit-trc [{{{:keys [dump dump-path]} :log} :conf :keys [tstore]} session-id trace-id chnum data]
  (try
    ; TODO weryfikacja argumentów
    (.handleTraceData ^Collector (:collector @tstore) session-id trace-id chnum data)
    (when dump (dump-trace-req dump-path "/agent/submit/trc" session-id nil trace-id data))
    (rhr/accepted)
    (catch ZorkaRuntimeException _
      (rhr/unauthorized {:reason "invalid or missing session ID header"}))
    (catch Exception e
      (log/error e "Error processing TRC data: " (Base64/encode data false))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn trace-desc-default [t]
  (or
    (when (and (:klass t) (:method t)) (str (:klass t) "." (:method t) "()"))
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

