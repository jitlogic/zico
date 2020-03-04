(ns zico.trace
  (:require
    [ring.util.http-response :as rhr]
    [zico.util :as zu]
    [clojure.tools.logging :as log]
    [clojure.data.json :as json]
    [slingshot.slingshot :refer [try+]]
    [clj-http.conn-mgr :as chcm])
  (:import
    (com.jitlogic.zorka.common.util Base64 ZorkaRuntimeException)
    (com.jitlogic.zorka.common.collector
      TraceDataResult TraceStatsResult NoSuchSessionException Collector
      TraceDataExtractingProcessor TraceStatsExtractingProcessor TraceChunkData)
    (com.jitlogic.zorka.common.tracedata HttpConstants TraceMarker)
    (java.util ArrayList Collection)))

(defn tstore-type [app-state & _]
  (-> app-state :conf :tstore :type))

(defn tcd->rest [^TraceChunkData tcd & {:keys [chunks?]}]
  "Converts TraceChunkData collector internal object to ChunkMetadata REST object."
  (when (some? tcd)
    (merge
      {:traceid  (.getTraceIdHex tcd)
       :spanid   (.getSpanIdHex tcd)
       :parentid (.getParentIdHex tcd)
       :ttype    (.getTtype tcd)
       :tst      (.getTstamp tcd)
       :tstamp   (zu/millis->iso-time (.getTstamp tcd))
       :duration (.getDuration tcd)
       :calls    (.getCalls tcd)
       :error    (.hasFlag tcd TraceMarker/ERROR_MARK)
       :errors   (.getErrors tcd)
       :recs     (.getRecs tcd)
       :klass    (.getKlass tcd)
       :method   (.getMethod tcd)
       :tsnum    1}
      (when chunks? {:tdata (.getTraceData tcd)})
      (when (.getAttrs tcd) {:attrs (into {} (for [[k v] (.getAttrs tcd)] {k v}))})
      )))

(defn rest->tcd [{:keys [traceid spanid parentid chnum tsnum tst duration klass method ttype recs calls errors tdata sdata]}]
  "Converts ChunkMetadata REST object to TraceChunkData collector internal object."
  (let [tcd (TraceChunkData. traceid spanid parentid chnum)]
    (when tst (.setTstamp tcd tst))
    (when tsnum (.setTsNum tcd tsnum))
    (when duration (.setDuration tcd duration))
    (when klass (.setKlass tcd klass))
    (when method (.setMethod tcd method))
    (when ttype (.setTtype tcd ttype))
    (when recs (.setRecs tcd (.intValue recs)))
    (when calls (.setCalls tcd (.intValue calls)))
    (when errors (.setErrors tcd (.intValue errors)))
    (when tdata (.setTraceData tcd (zu/b64dec tdata)))
    (when sdata (.setSymbolData tcd (zu/b64dec sdata)))
    tcd))

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

(defmulti trace-search "Searches trace store" tstore-type)

(defn trace-desc-default [t]
  (or
    (when (and (:klass t) (:method t)) (str (:klass t) "." (:method t) "()"))
    (get-in t [:attrs "call.method"])
    (str "TRACE@(" (:tstamp t) ")")))

(defn trace-desc [dfn t]
  (let [c (get-in t [:attrs "component"])]
    (cond
      (nil? c) (trace-desc-default t)
      (contains? dfn c) ((dfn c) t)
      :else (trace-desc-default t))))

(defn trace-search+desc [{:keys [trace-desc-fns] :as app-state} query]
  (for [t (trace-search app-state query {})]
    (assoc t :desc (trace-desc trace-desc-fns t))))


(defn trace-detail [app-state traceid spanid]
  (let [chunks (trace-search app-state {:traceid traceid, :spanid spanid, :spans-only true} {:chunks? true, :raw? true})
        rslt (TraceDataExtractingProcessor/extractTrace (ArrayList. ^Collection chunks))]
    (tdr->tr rslt)))

(defn tsr->ts [^TraceStatsResult tsr]
  "Converts TraceStatsResult to clojure map matching TraceStats schema"
  {:mid (.getMid tsr)
   :recs (.getRecs tsr)
   :errors (.getErrors tsr)
   :sum-duration (.getSumDuration tsr)
   :max-duration (.getMaxDuration tsr)
   :min-duration (.getMinDuration tsr)
   :method (.getMethod tsr)})

(defn trace-stats [app-state traceid spanid]
  (let [chunks (trace-search app-state {:traceid traceid :spanid spanid} {:chunks? true, :raw? true}),
        rslt (TraceStatsExtractingProcessor/extractStats (ArrayList. ^Collection chunks))]
    (vec (map tsr->ts rslt))))

(defmulti attr-vals "Returns all values of given attribute" tstore-type)

(defn dump-trace-req [path uri session-id session-reset trace-id data]
  (let [headers (merge {"content-type"                     [HttpConstants/ZORKA_CBOR_CONTENT_TYPE]
                        HttpConstants/HDR_ZORKA_SESSION_ID [session-id]}
                       (when session-reset {HttpConstants/HDR_ZORKA_SESSION_RESET [session-reset]})
                       (when trace-id {HttpConstants/HDR_ZORKA_TRACE_ID [trace-id]}))]
    (locking path
      (spit path (str (json/write-str {:uri uri, :headers headers, :body (Base64/encode data false)}) "\n") :append true))))

(defn submit-agd [{{{:keys [dump dump-path]} :log} :conf :keys [trace-collector]} session-id session-reset data]
  "Handles agent data submissions (symbols, method records)"
  (try
    (.handleAgentData ^Collector trace-collector session-id session-reset data)
    (when dump (dump-trace-req dump-path "/agent/submit/agd" session-id session-reset nil data))
    (rhr/accepted)
    ; TODO session-renew - handle broken sessions
    ;(catch Exception _ (rhr/bad-request {:reason "Missing session UUID header."}))
    (catch NoSuchSessionException e
      (log/debug "Non-existent session:" (.getMessage e))
      (rhr/unauthorized (json/write-str {:reason "invalid or missing session ID header"})))
    (catch Exception e
      (log/error e (str "Error processing AGD data: " (String. ^bytes data "UTF-8")))
      (rhr/internal-server-error (json/write-str {:reason "internal error"})))))

(defn submit-trc [{{{:keys [dump dump-path]} :log} :conf :keys [trace-collector]} session-id trace-id chnum data]
  "Handles trace data submissions (traces)"
  (try+
    (.handleTraceData ^Collector trace-collector session-id trace-id chnum data)
    (when dump (dump-trace-req dump-path "/agent/submit/trc" session-id nil trace-id data))
    (rhr/accepted)
    (catch NoSuchSessionException e
      (log/debug "Non-existent session:" (.getMessage e))
      (rhr/unauthorized (json/write-str {:reason "invalid or missing session ID header"})))
    (catch [:type :field-limit-exceeded] _
      (log/debug "Redundant :field-limit-exceeded error (ignored)"))
    (catch ZorkaRuntimeException e
      (log/error e "Malformed data or missing header")
      (rhr/unauthorized (json/write-str {:reason "malformed data or missing header"})))
    (catch Exception e
      (log/error e "Error processing TRC data: " (Base64/encode data false))
      (rhr/internal-server-error (json/write-str {:reason "internal error"})))))

(def JKS-KEYS [:trust-store :trust-store-type :trust-store-pass :keystore :keystore-type :keystore-pass])
(def CMGR-KEYS (concat JKS-KEYS [:timeout :threads :insecure? :default-per-route]))

(defmulti new-trace-store "Creates new trace store" tstore-type)

(defn with-tracer-components [{{conf :tstore :keys [trace-types]} :conf :as app-state} old-state]
  (let [conn-mgr (chcm/make-reusable-conn-manager (select-keys conf CMGR-KEYS))
        tstore-lock (:tstore-lock old-state (Object.)),
        tstore-tsnum (:tstore-tsnum app-state (atom 0))
        app-state (assoc app-state :tstore-lock tstore-lock, :tstore-tsnum tstore-tsnum)
        app-state (assoc-in app-state [:conf :tstore :connection-manager] conn-mgr)
        {:keys [collector store]} (new-trace-store app-state old-state)
        trace-desc-fns (into {} (for [tt (vals trace-types)] {(:component tt) #(or (get-in % [:attrs (name (:render tt))]) (trace-desc-default %))}))]
    (assoc app-state
      :trace-store store,
      :tstore-lock tstore-lock
      :trace-collector collector,
      :tstore-tsnum tstore-tsnum,
      :trace-desc-fns trace-desc-fns)))

