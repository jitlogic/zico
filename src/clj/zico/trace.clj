(ns zico.trace
  (:require
    [clj-time.coerce :as ctc]
    [clj-time.format :as ctf]
    [ring.util.http-response :as rhr]
    [zico.util :as zu]
    [zico.elastic :as ze]
    [zico.memstore :as zm]
    [clojure.tools.logging :as log]
    [clojure.data.json :as json])
  (:import
    (com.jitlogic.zorka.common.util Base64 ZorkaRuntimeException)
    (com.jitlogic.zorka.common.collector TraceChunkData Collector TraceDataExtractor TraceDataResult TraceStatsExtractor TraceStatsResult)
    (com.jitlogic.zorka.common.tracedata HttpConstants)))


(defn chunks->tree-node [node cgroups]
  (if-let [children (get cgroups (:spanid node))]
    (assoc node :children
                (sort-by :tst
                  (for [c (zu/filter-unique :spanid children)]
                    (chunks->tree-node c cgroups))))
    node))

(defn chunks->tree [chunks]
  (let [cgroups (group-by #(or (:parentid %) :root) chunks)]
    (when (:root cgroups)
      (chunks->tree-node (first (:root cgroups)) cgroups))))

(defn trace-search [{:keys [trace-desc tstore-state] :as app-state} query]
  (map trace-desc ((:search @tstore-state) app-state query)))


(defn tdr->tr [^TraceDataResult tdr]
  "Converts TraceDataRecord to clojure map matching TraceRecord schema."
  (when tdr
    (merge
      {:method   (str (.getMethod tdr)),
       :pos      (.getChunkPos tdr),
       :errors   (.getErrors tdr),
       :duration (- (.getTstop tdr) (.getTstart tdr))
       :tstart   (.getTstart tdr)}
      (when-let [attrs (.getAttributes tdr)]
        {:attrs (into {} attrs)})
      (when-let [children (.getChildren tdr)]
        {:children (map tdr->tr children)}))))

(defn trace-detail [{:keys [tstore-state] :as app-state} traceid spanid]
  (tdr->tr ((:detail @tstore-state) app-state traceid spanid)))

(defn tsr->ts [^TraceStatsResult tsr]
  "Converts TraceStatsResult to clojure map matching TraceStats schema"
  {:mid (.getMid tsr)
   :recs (.getRecs tsr)
   :errors (.getErrors tsr)
   :sum-duration (.getSumDuration tsr)
   :max-duration (.getMaxDuration tsr)
   :min-duration (.getMinDuration tsr)
   :method (.getMethod tsr)})

(defn trace-stats [{:keys [tstore-state] :as app-state} traceid spanid]
  (let [rslt ((:stats @tstore-state) app-state traceid spanid)]
    (vec (map tsr->ts rslt))))

(defn dump-trace-req [path uri session-id session-reset trace-id data]
  (let [headers (merge {"content-type"                     [HttpConstants/ZORKA_CBOR_CONTENT_TYPE]
                        HttpConstants/HDR_ZORKA_SESSION_ID [session-id]}
                       (when session-reset {HttpConstants/HDR_ZORKA_SESSION_RESET [session-reset]})
                       (when trace-id {HttpConstants/HDR_ZORKA_TRACE_ID [trace-id]}))]
    (locking path
      (spit path (str (json/write-str {:uri uri, :headers headers, :body (Base64/encode data false)}) "\n") :append true))))

(defn submit-agd [{{{:keys [dump dump-path]} :log} :conf :keys [tstore-state]} session-id session-reset data]
  (try
    (.handleAgentData (:collector @tstore-state) session-id session-reset data)
    (when dump (dump-trace-req dump-path "/agent/submit/agd" session-id session-reset nil data))
    (rhr/accepted)
    ; TODO session-renew - handle broken sessions
    ;(catch Exception _ (rhr/bad-request {:reason "Missing session UUID header."}))
    (catch Exception e
      (log/error e (str "Error processing AGD data: " (String. ^bytes data "UTF-8")))
      (rhr/internal-server-error (json/write-str {:reason "internal error"})))))


(defn submit-trc [{{{:keys [dump dump-path]} :log} :conf :keys [tstore-state]} session-id trace-id chnum data]
  (try
    (.handleTraceData (:collector @tstore-state) session-id trace-id chnum data)
    (when dump (dump-trace-req dump-path "/agent/submit/trc" session-id nil trace-id data))
    (rhr/accepted)
    (catch ZorkaRuntimeException e
      (log/error e "Invalid session or missing header")
      (rhr/unauthorized (json/write-str {:reason "invalid or missing session ID header"})))
    (catch Exception e
      (log/error e "Error processing TRC data: " (Base64/encode data false))
      (rhr/internal-server-error (json/write-str {:reason "internal error"})))))


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


(defn with-tracer-components [{{{:keys [type]} :tstore} :conf :as app-state} old-state]
  (let [tstore-lock (or (:tstore-lock old-state) (Object.)),
        new-tstore (case type
                 :elastic (ze/elastic-trace-store app-state old-state)
                 :memory (zm/memory-trace-store app-state old-state)
                 (throw (ex-info "No trace store type selected." {}))),
        tfn (trace-desc-fn (:conf app-state))]
    (assoc app-state
      :tstore-state (atom new-tstore),
      :tstore-lock tstore-lock,
      :trace-desc #(assoc % :desc (tfn %)))))

