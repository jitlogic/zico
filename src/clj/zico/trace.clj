(ns zico.trace
  (:require
    [clj-time.coerce :as ctc]
    [clj-time.format :as ctf]
    [ring.util.http-response :as rhr]
    [zico.util :as zu]
    [clojure.tools.logging :as log]
    [clojure.data.json :as json])
  (:import
    (java.io File)
    (io.zorka.tdb.store
      RotatingTraceStore TraceRecordFilter
      TraceRecord RecursiveTraceDataRetriever ObjectRef StackData TraceStatsRecordFilter TraceStatsResultItem TraceStore TraceSearchQuery ChunkMetadata Tid)
    (java.util HashMap Map List ArrayList)

    (com.jitlogic.zorka.common.util ZorkaUtil Base64)
    (io.zorka.tdb ZicoException)
    (com.jitlogic.zorka.cbor HttpConstants)
    (io.zorka.tdb.text StructuredTextIndex)))


(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))

(defn parse-hex-tid [^String s]
  "Parses trace or span ID. Returns vector of two components, if second component does not exist, 0."
  (cond
    (nil? s) nil
    (re-matches #"[0-9a-fA-F]{16}" s) [(.longValue (BigInteger. s 16)) 0]
    (re-matches #"[0-9a-fA-F]{32}" s) [(.longValue (BigInteger. (.substring s 0 16) 16))
                                       (.longValue (BigInteger. (.substring s 16 32) 16))]
    :else nil))


(defn parse-search-query [{:keys [fetch-attrs errors-only spans-only
                                  min-tstamp max-tstamp min-duration
                                  attr-matches text match-start match-end]}]
  (let [q (TraceSearchQuery.)]
    (when fetch-attrs (.withFetchAttrs q))
    (when errors-only (.withErrorsOnly q))
    (when spans-only (.withSpansOnly q))
    (when-not (empty? text) (.setText q text))
    (when match-start (.withMatchStart q))
    (when match-end (.withMatchEnd q))
    (when min-tstamp (.setMinTstamp q (* 1000000 (ctc/to-long (ctf/parse PARAM-FORMATTER min-tstamp)))))
    (when max-tstamp (.setMaxTstamp q (* 1000000 (ctc/to-long (ctf/parse PARAM-FORMATTER max-tstamp)))))
    (when min-duration (.setMinDuration q min-duration))
    (when attr-matches
      (doseq [[k v] attr-matches]
        (.withAttrMatch q (str k) (str v))))
    q))

(defn from-chunk-metadata [tfn c]
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
      (when-let [attrs (.getAttributes c)]
        {:attrs (into {} (for [[k v] attrs] {(str k) (str v)}))})
      (when (> (.size (.getChildren c)) 0)
        (let [children (doall (for [c (.getChildren c)] (from-chunk-metadata tfn c)))]
          (when children {:children children}))))
    tfn))

(defn trace-get [{:keys [tstore trace-desc]} tid]
  (let [[t1 t2] (parse-hex-tid tid)]
    (from-chunk-metadata trace-desc (.getTrace tstore (Tid/t t1 t2) true))))

(defn trace-search [{:keys [tstore trace-desc]} query]
  (for [c (.search tstore (parse-search-query query) (:limit query 50) (:offset query 0))]
    (from-chunk-metadata trace-desc c)))


(defn resolve-attr-obj [obj resolver]
  (cond
    (instance? ObjectRef obj)
    (let [id (.getId obj)]
      (if (> id 0)
        (.resolve resolver (.getId obj))
        "#!REF[0]" ; TODO This is bug and should be tracked in trace generation/ingestion code
        ))
    (or (instance? Map obj) (map? obj))
    (into
      {}
      (for [[k v] obj]
        {(resolve-attr-obj k resolver) (resolve-attr-obj v resolver)}))
    (or (instance? List obj) (list? obj))
    (into [] (for [o obj] (resolve-attr-obj o resolver)))))


(defn resolve-exception [^StructuredTextIndex resolver eid]
  (let [edata (.getExceptionData resolver eid true)]
    (when edata
      {:class (.resolve resolver (.getClassId edata))
       :msg (.resolve resolver (.getMsgId edata))
       :stack (for [^StackData si (.getStackTrace edata)]
                {:class (.resolve resolver (.getClassId si))
                 :method (.resolve resolver (.getMethodId si))
                 :file (.resolve resolver (.getFileId si))
                 :line (.getLineNum si)})})))

(def RE-METHOD-DESC #"(.*)\s+(.*)\.(.+)\.([^\(]+)(\(.*\))")

(defn parse-method-str [s]
  ; TODO dedicated StructuredTextIndex method that returns method description already parsed
  (when s
    (when-let [[_ r p c m a] (re-matches RE-METHOD-DESC s)]
      (let [cs (.split c "\\." 0), cl (alength cs)]
        {:result r, :package p, :class c, :method m, :args a}))))


(defn trace-record-filter []
  "Returns trace record filter "
  (reify
    TraceRecordFilter
    (filter [_ ^TraceRecord tr ^StructuredTextIndex resolver]
      (let [children (.getChildren tr), attrs (.getAttrs tr),
            method (.resolve resolver (.getMid tr))
            ]
        (merge
          {:method   (parse-method-str method)
           :pos      (.getPos tr)
           :errors   (.getErrors tr)
           :tstart   (.getTstart tr)
           :duration (- (.getTstop tr) (.getTstart tr))}
          (when children {:children children})
          (when attrs {:attrs (resolve-attr-obj attrs resolver)})
          (when (not= 0 (.getEid tr)) {:exception (resolve-exception resolver (.getEid tr))})
          )))))


(defn trace-detail [{:keys [tstore]} stack-limit tid sid]
  (let [[tid1 tid2] (parse-hex-tid tid), [sid1 _] (parse-hex-tid sid),
        rtr (doto (RecursiveTraceDataRetriever. (trace-record-filter)) (.setStackLimit stack-limit))
        rslt (.retrieve tstore (Tid/s tid1 tid2 sid1) rtr)]
    (merge {:trace-id tid :span-id sid} rslt)))


(defn trace-stats [{:keys [tstore]} tid sid]
  (let [[tid1 tid2] (parse-hex-tid tid), [sid1 _] (parse-hex-tid sid),
        f (TraceStatsRecordFilter.), rtr (RecursiveTraceDataRetriever. f)]
    (.retrieve tstore (Tid/s tid1 tid2 sid1) rtr)
    (vec
      (for [^TraceStatsResultItem s (.getStats f)]
        {:mid          (.getMid s),
         :recs         (.getRecs s),
         :errors       (.getErrors s),
         :sum-duration (.getSumDuration s),
         :max-duration (.getMinDuration s),
         :min-duration (.getMaxDuration s),
         :method       (.getMethod s)}))))

(defn dump-trace-req [path uri session-id session-reset trace-id data]
  (let [headers (merge {"content-type" [HttpConstants/ZORKA_CBOR_CONTENT_TYPE]
                        HttpConstants/HDR_ZORKA_SESSION_ID [session-id]}
                       (when session-reset {HttpConstants/HDR_ZORKA_SESSION_RESET [session-reset]})
                       (when trace-id {HttpConstants/HDR_ZORKA_TRACE_ID [trace-id]}))]
    (locking path
      ; TODO use json/write, current implementation is inefficient
      (spit path (str (json/write-str {:uri uri, :headers headers, :body (Base64/encode data false)}) "\n") :append true))))

(defn submit-agd [{{{:keys [dump dump-path]} :log} :conf :keys [tstore]} session-id session-reset data]
  (try
    (.handleAgentData tstore session-id (= "true" session-reset) data)
    (when dump (dump-trace-req dump-path "/agent/submit/agd" session-id session-reset nil data))
    (rhr/accepted)
    ; TODO session-renew - handle broken sessions
    ;(catch Exception _ (rhr/bad-request {:reason "Missing session UUID header."}))
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch Exception e
      (log/error e (str "Error processing AGD data: " (String. ^bytes data "UTF-8")))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn submit-trc [{{{:keys [dump dump-path]} :log} :conf :keys [tstore]} session-id trace-id data]
  (try
    ; TODO weryfikacja argumentów
    (if-let [[tid1 tid2] (parse-hex-tid trace-id)]
      (do
        (.handleTraceData tstore session-id data (ChunkMetadata. tid1 tid2 0 0 0))
        (when dump (dump-trace-req dump-path "/agent/submit/trc" session-id nil trace-id data)))
      (rhr/bad-request {:reason "Invalid trace ID header"}))
    (rhr/accepted)
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch ZicoException _
      (rhr/unauthorized {:reason "invalid or missing session ID header"}))
    (catch Exception e
      (log/error e "Error processing TRC data: " (Base64/encode data false))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn open-tstore [new-conf old-conf old-store]
  (let [old-root (when (:path old-conf) (File. ^String (:path old-conf))),
        idx-cache (if old-store (.getIndexerCache old-store) (HashMap.))
        new-root (File. ^String (:path new-conf)), new-props (zu/conf-to-props new-conf "store.")]
    (when-not (.exists new-root) (.mkdirs new-root))        ; TODO handle errors here, check if directory is writable etc.
    (cond
      (or (nil? old-store) (not (= old-root new-root)))
      (let [store (RotatingTraceStore. new-root new-props idx-cache)]
        (when old-store                                     ; TODO reconfigure without closing
          (future
            (log/info "Waiting for old trace store to close.")
            (Thread/sleep 10000)
            (.close old-store)
            (log/info "Old store closed.")))
        store)
      (not= old-conf new-conf) old-store                    ; TODO
      :else old-store)))

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
          (assoc app-state :tstore (open-tstore new-conf old-conf tstore))
          app-state)
        tfn (trace-desc-fn (:conf app-state))]
    (assoc new-state :trace-desc #(assoc % :desc (tfn %)))))

