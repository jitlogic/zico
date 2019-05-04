(ns zico.trace
  (:require
    [clj-time.coerce :as ctc]
    [clj-time.format :as ctf]
    [ring.util.http-response :as rhr]
    [zico.util :as zbu :refer [to-int]]
    [clojure.tools.logging :as log])
  (:import
    (java.io File)
    (io.zorka.tdb.store
      RotatingTraceStore TraceRecordFilter
      TraceRecord RecursiveTraceDataRetriever ObjectRef StackData TraceStatsRecordFilter TraceStatsResultItem TraceStore)
    (java.util HashMap Map List ArrayList)
    (io.zorka.tdb.meta ChunkMetadata StructuredTextIndex)
    (io.zorka.tdb.search TraceSearchQuery SortOrder QmiNode)
    (io.zorka.tdb.search.lsn AndExprNode OrExprNode)
    (io.zorka.tdb.search.ssn TextNode)
    (io.zorka.tdb.search.tsn KeyValSearchNode)
    (io.zorka.tdb.util ZicoMaintThread)
    (com.jitlogic.zorka.common.util ZorkaUtil)
    (io.zorka.tdb ZicoException)))


(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))


(defn lmap [f coll]
  (let [lst (ArrayList.)]
    (doseq [n (map f coll)]
      (.add lst n))
    lst))

(defn parse-hex-tid [^String s]
  "Parses trace or span ID. Returns vector of two components, if second component does not exist, 0."
  (cond
    (nil? s) nil
    (re-matches #"[0-9a-fA-F]{16}" s) [(.longValue (BigInteger. s 16)) 0]
    (re-matches #"[0-9a-fA-F]{32}" s) [(.longValue (BigInteger. (.substring s 0 16) 16))
                                       (.longValue (BigInteger. (.substring s 16 32) 16))]
    :else nil))

(defn parse-qmi-node [q]
  (doto (QmiNode.)
    (.setMinDuration (to-int (:min-duration q 0)))
    (.setMaxDuration (to-int (:max-duration q Long/MAX_VALUE)))
    (.setMinCalls (to-int (:min-calls q 0)))
    (.setMaxCalls (to-int (:max-calls q Long/MAX_VALUE)))
    (.setMinErrs (to-int (:min-errors q 0)))
    (.setMaxErrs (to-int (:max-errors q Long/MAX_VALUE)))
    (.setMinRecs (to-int (:min-recs q 0)))
    (.setMaxRecs (to-int (:max-recs q Long/MAX_VALUE)))
    (.setTstart (ctc/to-long (ctf/parse PARAM-FORMATTER (:tstart q "20100101T000000Z"))))
    (.setTstop (ctc/to-long (ctf/parse PARAM-FORMATTER (:tstop q "20300101T000000Z"))))))


(def TYPES {"or" :or, "text" :text, "xtext" :xtext, "kv" :kv})


; TODO this is kludge, get rid of this function.
(defn prep-search-node [q]
  (let [t (get q "type")]
    (cond
      (contains? q :type) q
      (nil? t) {}
      (= "kv" t) (assoc q :type :kv)
      :else
      (let [q (into {} (for [[k v] q] {(keyword k) v}))]
        (assoc q :type t)))))


(defn parse-search-node [q]
  (if (string? q)
    (TextNode. ^String q true true)
    (let [q (prep-search-node q)]
      (case (TYPES (:type q) (:type q))
        :and (AndExprNode. (lmap parse-search-node (:args q)))
        :or (OrExprNode. (lmap parse-search-node (:args q)))
        :text (TextNode. ^String (:text q) ^Boolean (:match-start q false) ^Boolean (:match-end q false))
        :xtext (TextNode. ^String (:text q) ^Boolean (:match-start q true) ^Boolean (:match-end q true))
        :kv (KeyValSearchNode. (:key q) (parse-search-node (:val q)))
        nil))))


(defn parse-search-query [q]
  (let [qmi (if (:qmi q) (parse-qmi-node (:qmi q)) (QmiNode.))
        node (if (:node q) (parse-search-node (:node q)))]
    (doto (TraceSearchQuery. qmi node)
      (.setLimit (:limit q 50))
      (.setOffset (:offset q 0))
      (.setAfter (:after q 0))
      (.setSortOrder (case (:sort-order q :none)
                       :none SortOrder/NONE,
                       :duration SortOrder/DURATION,
                       :calls SortOrder/CALLS,
                       :recs SortOrder/RECS,
                       :errors SortOrder/ERRORS))
      (.setSortReverse (:sort-reverse q false))
      (.setDeepSearch (:deep-search q true))
      (.setFullInfo (:full-info q false)))))


(defn trace-search [{:keys [tstore]} query]
  (vec
    (take
      (:limit query 100)
      (for [r (.searchTraces tstore (parse-search-query query))]
        {:duration    (.getDuration r)
         :tst         (.getTstamp r)
         :tstamp      (zbu/str-time-yymmdd-hhmmss-sss (* 1000 (.getTstamp r)))
         :data-offs   (.getDataOffs r)
         :start-offs  (.getStartOffs r)
         :flags       #{}                                   ; TODO flagi
         :recs        (.getRecs r)
         :calls       (.getCalls r)
         :errs        (.getErrors r)
         }))))


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
  (when s
    (when-let [[_ r p c m a] (re-matches RE-METHOD-DESC s)]
      (let [cs (.split c "\\." 0), cl (alength cs)]
        {:result r, :package p, :class c, :method m, :args a}))))


(defn trace-record-filter [obj-store]
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
           :tstart (.getTstart tr)
           :duration (- (.getTstop tr) (.getTstart tr))}
          (when children {:children children})
          (when attrs {:attrs (resolve-attr-obj attrs resolver)})
          (when (not= 0 (.getEid tr)) {:exception (resolve-exception resolver (.getEid tr))})
          )))))


(defn trace-detail [{:keys [tstore obj-store]} stack-limit tid sid]
  (let [[tid1 tid2] (parse-hex-tid tid), [sid1 _] (parse-hex-tid sid),
        rtr (doto (RecursiveTraceDataRetriever. (trace-record-filter obj-store)) (.setStackLimit stack-limit))
        rslt (.retrieve tstore tid1 tid2 sid1 rtr)]
    (merge {:trace-id tid :span-id sid} rslt)))


(defn trace-stats [{:keys [tstore]} tid sid]
  (let [[tid1 tid2] (parse-hex-tid tid), [sid1 _] (parse-hex-tid sid),
        f (TraceStatsRecordFilter.), rtr (RecursiveTraceDataRetriever. f)]
    (.retrieve tstore tid1 tid2 sid1 rtr)
    (vec
      (for [^TraceStatsResultItem s (.getStats f)]
        {:mid          (.getMid s),
         :recs         (.getRecs s),
         :errors       (.getErrors s),
         :sum-duration (.getSumDuration s),
         :max-duration (.getMinDuration s),
         :min-duration (.getMaxDuration s),
         :method       (.getMethod s)}))))


(defn submit-agd [{:keys [tstore]} session-id session-renew data]
  (try
    (.handleAgentData tstore session-id (= "true" session-renew) data)
    (rhr/accepted)
    ; TODO session-renew - handle broken sessions
    ;(catch Exception _ (rhr/bad-request {:reason "Missing session UUID header."}))
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch Exception e
      (log/error e (str "Error processing AGD data: " (String. ^bytes data "UTF-8")))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn submit-trc [{:keys [tstore]} session-id trace-id data]
  (try
    ; TODO weryfikacja argumentów
    (if-let [[tid1 tid2] (parse-hex-tid trace-id)]
      (.handleTraceData tstore session-id data (ChunkMetadata. tid1 tid2 0 0 0))
      (rhr/bad-request {:reason "Invalid trace ID header"}))
    (rhr/accepted)
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch ZicoException _
      (rhr/unauthorized {:reason "invalid or missing session ID header"}))
    (catch Exception e
      (log/error e "Error processing TRC data: " (ZorkaUtil/hex data))
      (rhr/internal-server-error {:reason "internal error"}))))


(defn open-tstore [new-conf old-conf old-store]
  (let [old-root (when (:path old-conf) (File. ^String (:path old-conf))),
        idx-cache (if old-store (.getIndexerCache old-store) (HashMap.))
        new-root (File. ^String (:path new-conf)), new-props (zbu/conf-to-props new-conf "store.")]
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
      (not= old-conf new-conf)
      (doto old-store
        (.configure new-props))
      :else old-store)))


(defn with-tracer-components [{{new-conf :tstore} :conf obj-store :obj-store :as app-state}
                              {{old-conf :tstore} :conf :keys [tstore maint-threads]}]
  (if (:enabled new-conf true)
    (let [tstore (open-tstore new-conf old-conf tstore)
          nmt (doall (for [n (range (:maint-threads new-conf 2))]
                       (ZicoMaintThread. (str n) (* 1000 (:maint-interval new-conf 10)) tstore)))]
      (doseq [t maint-threads]
        (.stop t))
      (assoc app-state
        :maint-threads nmt
        :tstore tstore))
    app-state))

