(ns zico.trace
  (:require
    [taoensso.timbre :as log]
    [slingshot.slingshot :refer [throw+ try+]]
    [zico.util :as zutl]
    [zico.objstore :as zobj]
    [clj-time.coerce :as ctc]
    [clj-time.format :as ctf])
  (:import
    (java.io File)
    (io.zorka.tdb.store
      RotatingTraceStore TraceStore TemplatingMetadataProcessor TraceRecordFilter
      TraceRecord RecursiveTraceDataRetriever ObjectRef StackData TraceTypeResolver TraceSearchResultItem TraceStatsRecordFilter TraceStatsResultItem)
    (java.util HashMap Map List ArrayList)
    (io.zorka.tdb.meta ChunkMetadata StructuredTextIndex)
    (io.zorka.tdb MissingSessionException)
    (io.zorka.tdb.search TraceSearchQuery SortOrder QmiNode)
    (io.zorka.tdb.search.lsn AndExprNode OrExprNode)
    (io.zorka.tdb.search.ssn TextNode)
    (io.zorka.tdb.search.tsn KeyValSearchNode)))


(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))


(defn lmap [f coll]
  (let [lst (ArrayList.)]
    (doseq [n (map f coll)]
      (.add lst n))
    lst))


(defn parse-qmi-node [q]
  (doto (QmiNode.)
    (.setAppId (zobj/extract-uuid-seq (:app q)))
    (.setEnvId (zobj/extract-uuid-seq (:env q)))
    (.setHostId (zobj/extract-uuid-seq (:host q)))
    (.setTypeId (zobj/extract-uuid-seq (:ttype q)))
    (.setMinDuration (:min-duration q 0))
    (.setMaxDuration (:max-duration q Long/MAX_VALUE))
    (.setMinCalls (:min-calls q 0))
    (.setMaxCalls (:max-calls q Long/MAX_VALUE))
    (.setMinErrs (:min-errors q 0))
    (.setMaxErrs (:max-errors q Long/MAX_VALUE))
    (.setMinRecs (:min-recs q 0))
    (.setMaxRecs (:max-recs q Long/MAX_VALUE))
    (.setTstart (ctc/to-long (ctf/parse PARAM-FORMATTER (:tstart q "20100101T000000Z"))))
    (.setTstop (ctc/to-long (ctf/parse PARAM-FORMATTER (:tstop q "20300101T000000Z"))))
    (.setDtraceUuid (:dtrace-uuid q))
    (.setDtraceTid (:dtrace-tid q))))


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
        :text (TextNode. (:text q) (:match-start q false) (:match-end q false))
        :xtext (TextNode. (:text q) (:match-start q true) (:match-end q true))
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


(defn find-and-map-by-id [obj-store fexpr]
  (into {} (for [uuid (zobj/find-obj obj-store fexpr)]
             {(zobj/extract-uuid-seq uuid) uuid})))


(defn trace-search [{:keys [obj-store trace-store] :as app-state}
                    {:keys [data] :as req}]
  (let [query (parse-search-query data)
        limit (:limit data 100)
        apps (find-and-map-by-id obj-store {:class :app})
        envs (find-and-map-by-id obj-store {:class :env})
        ttps (find-and-map-by-id obj-store {:class :ttype})
        hids (find-and-map-by-id obj-store {:class :host})
        rslt (for [^TraceSearchResultItem r (.searchTraces trace-store query)]
               {:uuid        (.getUuid r),
                :lcid        (.getChunkId r)
                :descr       (.getDescription r)
                :duration    (.getDuration r)
                :ttype       (ttps (.getTypeId r))
                :app         (apps (.getAppId r))
                :env         (envs (.getEnvId r))
                :host        (hids (.getHostId r))
                :tst         (.getTstamp r)
                :tstamp      (zutl/str-time-yymmdd-hhmmss-sss (* 1000 (.getTstamp r)))
                :data-offs   (.getDataOffs r)
                :start-offs  (.getStartOffs r)
                :flags       #{}                             ; TODO flagi
                :recs        (.getRecs r)
                :calls       (.getCalls r)
                :errs        (.getErrors r)
                :dtrace-uuid (.getDtraceUuid r)
                :dtrace-tid  (.getDtraceTid r)
                :dtrace-out  (.isDtraceOut r)
                })]
    {:status 200, :body {:type :rest, :data (vec (take limit rslt))}}))


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


(defn trace-record-filter [obj-store]
  "Returns trace record filter "
  (reify
    TraceRecordFilter
    (filter [_ ^TraceRecord tr ^StructuredTextIndex resolver]
      (let [children (.getChildren tr), attrs (.getAttrs tr),
            method (.resolve resolver (.getMid tr))
            ]
        (merge
          {:method   (zutl/parse-method-str method)
           :pos      (.getPos tr)
           :errors   (.getErrors tr)
           :duration (- (.getTstop tr) (.getTstart tr))}
          (when children {:children children})
          (when attrs {:attrs (resolve-attr-obj attrs resolver)})
          (when (not= 0 (.getEid tr)) {:exception (resolve-exception resolver (.getEid tr))})
          (when (not= 0 (.getType tr)) {:ttype (first (zobj/find-obj obj-store {:class :ttype, :id (.getType tr)}))})
          )))))


(defn trace-detail [{:keys [trace-store obj-store] :as app-state} stack-limit uuid]
  (let [rtr (doto (RecursiveTraceDataRetriever. (trace-record-filter obj-store)) (.setStackLimit stack-limit))
        rslt (.retrieve trace-store uuid rtr)]
    {:status 200, :body {:type :rest, :data (merge {:uuid uuid} rslt)}}))


(defn trace-detail-tid [{:keys [trace-store obj-store] :as app-state} stack-limit is-out tid]
  (let [query (TraceSearchQuery. (doto (QmiNode.) (.setDtraceTid tid)) nil)
        rslt (.searchTraces trace-store query)
        trc (first (for [r rslt :when (= is-out (.isDtraceOut r))] r))]
    (if (some? trc)
      (trace-detail app-state stack-limit (.getUuid trc))
      {:status 404})))


(defn trace-stats [{:keys [trace-store obj-store] :as app-state} uuid]
  (let [f (TraceStatsRecordFilter.), rtr (RecursiveTraceDataRetriever. f)]
    (.retrieve trace-store uuid rtr)
    {:status 200,
     :body {:type :rest,
            :data (for [^TraceStatsResultItem s (.getStats f)]
                    {:mid (.getMid s),
                     :recs (.getRecs s),
                     :errors (.getErrors s),
                     :sum-duration (.getSumDuration s),
                     :max-duration (.getMinDuration s),
                     :min-duration (.getMaxDuration s),
                     :method (.getMethod s)})
            }}))


(defn get-or-new [{:keys [obj-store] {{reg :register} :agent-conf} :conf :as app-state} class name]
  (if (some? name)
    (let [nobj (zobj/find-and-get-1 obj-store {:class class, :name name})]
      (cond
        (some? nobj) nobj
        (not (get reg class)) nil
        :else
        (let [nobj {:class class, :name name, :comment (str "New " class), :flags 1, :glyph "awe/cube"}]
          (zobj/put-obj obj-store nobj))))))


(defn get-host-attrs [{:keys [obj-store]} host-uuid]
  "Retrieves custom attributes for given host. Returns :ATTR -> 'value' map."
  (let [host-attrs (zobj/find-and-get obj-store {:class :hostattr, :hostuuid host-uuid})]
    (into {}
          (for [{:keys [attruuid attrval]} host-attrs
                :let [{attrname :name} (zobj/get-obj obj-store attruuid)]]
            {(keyword attrname) attrval}))))


(defn update-host-attrs [{:keys [obj-store] :as app-state} host-uuid attrs]
  "Adds or updates host attributes. Missing but previously posted attributes are not removed."
  (let [host-attrs (get-host-attrs app-state host-uuid)]
    (doseq [[k v] attrs :when (not= v (host-attrs k))
            :let [adesc (get-or-new app-state :attrdesc (name k))]
            :let [hattr (zobj/find-and-get-1 obj-store {:class :hostattr, :hostuuid host-uuid, :attruuid (:uuid adesc)})]]
      (zobj/put-obj obj-store (merge (or hattr {}) {:class :hostattr, :hostuuid host-uuid,
                                                    :attruuid (:uuid adesc), :attrval v})))))


(defn update-host-data [{:keys [obj-store] :as app-state}                 ; app-state
                         {{:keys [name app env attrs] :as data} :data :as req}
                         old-host]
  (let [app-uuid (or (:uuid (get-or-new app-state :app app)) (:app old-host))
        env-uuid (or (:uuid (get-or-new app-state :env env)) (:env old-host))
        new-host (merge old-host
                        (if app-uuid {:app app-uuid})
                        (if env-uuid {:env env-uuid})
                        (if name {:name name}))
        new-host (if (not= old-host new-host) (zobj/put-obj obj-store new-host))]
    (update-host-attrs app-state (:uuid new-host) attrs)
    new-host))


(defn register-host [{:keys [obj-store] :as app-state}                      ; app-state
                         {{:keys [name app env attrs]} :data} ; req
                         {:as hreg}]                          ; host-reg
  (let [app-uuid (or (:uuid (get-or-new app-state :app app)) (:app hreg))
        env-uuid (or (:uuid (get-or-new app-state :env env)) (:env hreg))]
    (cond
      (nil? app-uuid) (zutl/rest-error (str "No such application: " app) 400)
      (nil? env-uuid) (zutl/rest-error (str "No such environment:" env) 400)
      :else
      (let [hobj {:class :host, :app app-uuid, :env env-uuid, :name name,
                  :comment "Auto-registered host.", :flags 0x01, :authkey (zutl/random-string 16 zutl/ALPHA-STR)}
            hobj (zobj/put-obj obj-store hobj)]
        (update-host-attrs app-state (:uuid hobj) attrs)
        (zutl/rest-result {:uuid (:uuid hobj), :authkey (:authkey hobj)} 201)))))


(defn agent-register [{:keys [obj-store] {{reg :register} :agent-conf} :conf :as app-state}
                      {{:keys [rkey akey name uuid]} :data :as req}]
  (try+
    (if (and rkey name)
      (let [{:keys [regkey] :as hreg} (zobj/find-and-get-1 obj-store {:class :hostreg, :regkey rkey})
            {:keys [authkey] :as host} (when uuid (zobj/find-and-get-1 obj-store {:class :host, :uuid uuid}))]
        (cond
          (and akey (= akey authkey))
          (do
            (update-host-data app-state req host)
            (zutl/rest-result {:uuid (:uuid host), :authkey authkey}))
          (some? host) (zutl/rest-error "Access denied." 401)
          (not (:host reg)) (zutl/rest-error "No such host.")
          (not= rkey regkey) (zutl/rest-error "Access denied." 401)
          (= rkey regkey) (register-host app-state req hreg)
          :else (zutl/rest-error "Registration denied." 401)))
      (zutl/rest-error "Missing arguments." 400))
    (catch Object e
      (log/error e "Error registering host.")
      (zutl/rest-error "Internal error." 500))))


(defn agent-session [{:keys [obj-store trace-store]}        ; app-state
                     {{:keys [uuid authkey]} :data}]        ; req
  (if (and uuid authkey)
    (if-let [obj (zobj/get-obj obj-store uuid)]
      (if (= (:authkey obj) authkey)
        (zutl/rest-result {:session (.getSession ^TraceStore trace-store uuid)})
        (zutl/rest-error "Access denied." 401))
      (zutl/rest-error "No such agent." 400))
    (zutl/rest-error "Missing arguments." 400)))


(defn submit-agd [{:keys [trace-store]} agent-uuid session-uuid data]
  (try+
    ; TODO weryfikacja argumentów
    (locking trace-store
      (.handleAgentData trace-store agent-uuid session-uuid data))
    (zutl/rest-result {:result "Submitted"} 202)
    (catch MissingSessionException _
      (zutl/rest-error "Missing session UUID header." 412))
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch Object e
      (log/error e (str "Error processing AGD data: " data))
      (zutl/rest-error "Internal error." 500))))


(defn submit-trc [{:keys [obj-store trace-store] :as app-state} agent-uuid session-uuid trace-uuid data]
  (try+
    ; TODO weryfikacja argumentów
    (locking trace-store
      (if-let [agent (zobj/get-obj obj-store agent-uuid)]
        (do
          (.handleTraceData trace-store agent-uuid session-uuid trace-uuid data
            (doto (ChunkMetadata.)
              (.setAppId (zobj/extract-uuid-seq (:app agent)))
              (.setEnvId (zobj/extract-uuid-seq (:env agent)))))
          (zutl/rest-result {:result "Submitted"} 202))
        (zutl/rest-error "No such agent." 401)))
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch MissingSessionException _
      (zutl/rest-error "Missing session UUID header." 412))
    (catch Object e
      (log/error e "Error processing TRC data: " data)
      (zutl/rest-error "Internal error." 500))))


(defn trace-id-translate [obj-store data tn]
  (let [tid (get @data tn)]
    (cond
      (nil? tn) (do (log/error "Trace with NULL name obtained.") 0)
      tid tid
      :else
      (locking obj-store
        (when-not (get @data tn)
          (reset!
            data
            (into {} (for [{:keys [name uuid flags] :as r} (zobj/find-and-get obj-store {:class :ttype})]
                       {name {:tid (zobj/extract-uuid-seq uuid), :uuid uuid, :flags flags}}))))
        (when-not (get @data tn)
          (let [{:keys [uuid flags] :as tt} (zobj/put-obj
                     obj-store
                     {:class   :ttype,
                      :name tn,
                      :descr    (str "Trace type: " tn),
                      :glyph   "awe/cube",
                      :flags   1,
                      :comment "Auto-registered. Please edit."})
                ti {:tid (zobj/extract-uuid-seq uuid), :uuid uuid, :flags flags}]
            (swap! data assoc tn ti)))
        (get @data tn)))))


(defn trace-id-translator [obj-store]
  (let [data (atom {})]
    (reify
      TraceTypeResolver
      (resolve [_ tn]
        (let [{:keys [tid uuid flags] :as tr} (trace-id-translate obj-store data tn)]
          (when (= 0 (bit-and flags 0x08))
            (log/info "Marking trace type" uuid "as used.")
            (let [rec (zobj/get-obj obj-store uuid)
                  flags (bit-or (:flags rec) 0x08)]
              (zobj/put-obj obj-store (assoc rec :flags flags))
              (swap! data assoc tn (assoc tr :flags flags))))
          tid)))))


(defn open-trace-store [new-conf old-conf old-store indexer-executor cleaner-executor obj-store]
  (let [old-root (when (:path old-conf) (File. ^String (:path old-conf))),
        idx-cache (if old-store (.getIndexerCache old-store) (HashMap.))
        new-root (File. ^String (:path new-conf)), new-props (zutl/conf-to-props new-conf "store.")]
    (when-not (.exists new-root) (.mkdirs new-root))        ; TODO handle errors here, check if directory is writable etc.
    (cond
      (or (nil? old-store) (not (= old-root new-root)))
      (let [store (RotatingTraceStore.
                    new-root new-props
                    (trace-id-translator obj-store)
                    indexer-executor cleaner-executor idx-cache)]
        (when old-store                                     ; TODO reconfigure without closing
          (future
            (log/info "Waiting for old trace store to close.")
            (Thread/sleep 10000)
            (.close old-store)
            (log/info "Old store closed.")))
        store)
      (not= old-conf new-conf)
      (doto old-store
        (.configure new-props indexer-executor cleaner-executor))
      :else old-store)))


(defn with-tracer-components [{{new-conf :trace-store} :conf obj-store :obj-store :as app-state}
                              {{old-conf :trace-store} :conf :keys [indexer-executor cleaner-executor trace-store] :as old-app-state}]
  (if (:enabled new-conf true)
    (let [indexer-executor (zutl/simple-executor (:nthreads new-conf) (:nthreads old-conf) indexer-executor)
          cleaner-executor (zutl/simple-executor 1 1 cleaner-executor)
          trace-store (open-trace-store new-conf old-conf trace-store indexer-executor cleaner-executor obj-store)
          postproc (TemplatingMetadataProcessor.)
          trace-descs (into {} (for [obj (zobj/find-and-get obj-store {:class :ttype})] {(:uuid obj), (:descr obj)}))]
      (doseq [[uuid descr] trace-descs :when uuid :when descr :let [id (zobj/extract-uuid-seq uuid)]]
        (.putTemplate postproc id descr))
      (.setPostproc trace-store postproc)
      (assoc app-state
        :indexer-executor indexer-executor
        :cleaner-executor cleaner-executor
        :trace-store trace-store
        :trace-postproc postproc))
    app-state))

