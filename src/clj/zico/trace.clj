(ns zico.trace
  (:require
    [taoensso.timbre :as log]
    [zico.util :as zutl :refer [to-int]]
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
    (io.zorka.tdb.search.tsn KeyValSearchNode)
    (io.zorka.tdb.util ZicoMaintThread)
    (com.jitlogic.zorka.common.util ZorkaUtil)))


(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))


(defn lmap [f coll]
  (let [lst (ArrayList.)]
    (doseq [n (map f coll)]
      (.add lst n))
    lst))


(defn parse-qmi-node [q]
  (doto (QmiNode.)
    (.setAppId (to-int (:app q 0)))
    (.setEnvId (to-int (:env q 0)))
    (.setHostId (to-int (:host q 0)))
    (.setTypeId (to-int (:ttype q 0)))
    (.setMinDuration (to-int (:min-duration q 0)))
    (.setMaxDuration (to-int (:max-duration q Long/MAX_VALUE)))
    (.setMinCalls (to-int (:min-calls q 0)))
    (.setMaxCalls (to-int (:max-calls q Long/MAX_VALUE)))
    (.setMinErrs (to-int (:min-errors q 0)))
    (.setMaxErrs (to-int (:max-errors q Long/MAX_VALUE)))
    (.setMinRecs (to-int (:min-recs q 0)))
    (.setMaxRecs (to-int (:max-recs q Long/MAX_VALUE)))
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


(defn trace-search [{:keys [tstore]}
                    {:keys [data]}]
  (let [query (parse-search-query data)
        limit (:limit data 100)
        rslt (for [^TraceSearchResultItem r (.searchTraces tstore query)]
               {:uuid        (.getUuid r),
                :chunk-id    (.getChunkId r)
                :descr       (.getDescription r)
                :duration    (.getDuration r)
                :ttype       (.getTypeId r)
                :app         (.getAppId r)
                :env         (.getEnvId r)
                :host        (.getHostId r)
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


(defn trace-detail [{:keys [tstore obj-store]} stack-limit uuid]
  (let [rtr (doto (RecursiveTraceDataRetriever. (trace-record-filter obj-store)) (.setStackLimit stack-limit))
        rslt (.retrieve tstore uuid rtr)]
    {:status 200, :body {:type :rest, :data (merge {:uuid uuid} rslt)}}))


(defn trace-detail-tid [{:keys [tstore] :as app-state} stack-limit is-out tid]
  (let [query (TraceSearchQuery. (doto (QmiNode.) (.setDtraceTid tid)) nil)
        rslt (.searchTraces tstore query)
        trc (first (for [r rslt :when (= is-out (.isDtraceOut r))] r))]
    (if (some? trc)
      (trace-detail app-state stack-limit (.getUuid trc))
      {:status 404})))


(defn trace-stats [{:keys [tstore]} uuid]
  (let [f (TraceStatsRecordFilter.), rtr (RecursiveTraceDataRetriever. f)]
    (.retrieve tstore uuid rtr)
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


(defn get-or-new [{:keys [obj-store] {{reg :register} :agent} :conf :as app-state} class name]
  (if (some? name)
    (let [nobj (zobj/find-and-get-1 obj-store {:class class, :name name})]
      (cond
        (some? nobj) nobj
        (not (get reg class)) nil
        :else
        (let [nobj {:class class, :name name, :comment (str "New " class), :flags 1, :glyph "awe/cube"}]
          (zobj/put-obj obj-store nobj))))))


(defn get-host-attrs [{:keys [obj-store]} host-id]
  "Retrieves custom attributes for given host. Returns :ATTR -> 'value' map."
  (let [host-attrs (zobj/find-and-get obj-store {:class :hostattr, :hostid host-id})]
    (into {}
          (for [{:keys [attrid attrval]} host-attrs
                :let [{attrname :name} (zobj/get-obj obj-store {:class :attrdesc, :id attrid})]]
            {(keyword attrname) attrval}))))


(defn update-host-attrs [{:keys [obj-store] :as app-state} host-id attrs]
  "Adds or updates host attributes. Missing but previously posted attributes are not removed."
  (let [host-attrs (get-host-attrs app-state host-id)]
    (doseq [[k v] attrs :when (not= v (host-attrs k))
            :let [adesc (get-or-new app-state :attrdesc (name k))]
            :let [hattr (zobj/find-and-get-1 obj-store {:class :hostattr, :hostid host-id, :attrid (:id adesc)})]]
      (println "ADD: " {:class :hostattr, :hostid host-id, :attrid (:id adesc), :attrval v})
      (zobj/put-obj obj-store (merge (or hattr {}) {:class :hostattr, :hostid host-id,
                                                    :attrid (:id adesc), :attrval v})))))


(defn update-host-data [{:keys [obj-store] :as app-state}                 ; app-state
                         {{:keys [name app env attrs] :as data} :data :as req}
                         old-host]
  (let [app-id (or (:id (get-or-new app-state :app app)) (:app old-host))
        env-id (or (:id (get-or-new app-state :env env)) (:env old-host))
        new-host (merge old-host
                        (if app-id {:app app-id})
                        (if env-id {:env env-id})
                        (if name {:name name}))
        new-host (if (not= old-host new-host) (zobj/put-obj obj-store new-host))]
    (update-host-attrs app-state (:id new-host) attrs)
    new-host))


(defn register-host [{:keys [obj-store] :as app-state}                      ; app-state
                         {{:keys [name app env attrs]} :data} ; req
                         {:as hreg}]                          ; host-reg
  (let [app-id (or (:id (get-or-new app-state :app app)) (:app hreg))
        env-id (or (:id (get-or-new app-state :env env)) (:env hreg))]
    (cond
      (nil? app-id) (zutl/rest-error (str "No such application: " app) 400)
      (nil? env-id) (zutl/rest-error (str "No such environment:" env) 400)
      :else
      (let [hobj {:class :host, :app app-id, :env env-id, :name name,
                  :comment "Auto-registered host.", :flags 0x01, :authkey (zutl/random-string 16 zutl/ALPHA-STR)}
            hobj (zobj/put-obj obj-store hobj)]
        (update-host-attrs app-state (:id hobj) attrs)
        (zutl/rest-result {:id (:id hobj), :authkey (:authkey hobj)} 201)))))


(defn agent-register [{:keys [obj-store] {{reg :register} :agent} :conf :as app-state}
                      {{:keys [rkey akey name id]} :data :as req}]
  (try
    (if (and rkey name)
      (let [{:keys [regkey] :as hreg} (zobj/find-and-get-1 obj-store {:class :hostreg, :regkey rkey})
            {:keys [authkey] :as host} (when id (zobj/find-and-get-1 obj-store {:class :host, :id id}))]
        (cond
          (and (string? akey) (= akey authkey))
          (do
            (update-host-data app-state req host)
            (zutl/rest-result {:id (:id host), :authkey authkey}))
          (some? host) (zutl/rest-error "Access denied." 401)
          (not (:host reg)) (zutl/rest-error "No such host.")
          (not= rkey regkey) (zutl/rest-error "Access denied." 401)
          (= rkey regkey) (register-host app-state req hreg)
          :else (zutl/rest-error-logged "Registration denied." 401)))
      (zutl/rest-error-logged "Missing arguments." 400))
    (catch Exception e
      (log/error e "Error registering host.")
      (zutl/rest-error "Internal error." 500))))


(defn agent-session [{:keys [obj-store tstore]}        ; app-state
                     {{:keys [id authkey]} :data}]        ; req
  (if (and id authkey)
    (if-let [obj (zobj/get-obj obj-store {:class :host, :id id})]
      (if (= (:authkey obj) authkey)
        (zutl/rest-result {:session (.getSession ^TraceStore tstore id)})
        (zutl/rest-error-logged "Access denied." 401 id authkey))
      (zutl/rest-error-logged "No such agent." 400 id authkey))
    (zutl/rest-error-logged "Missing arguments." 400 id authkey)))


(defn submit-agd [{:keys [tstore]} agent-id session-uuid data]
  (try
    ; TODO weryfikacja argumentów
    (.handleAgentData tstore agent-id session-uuid data)
    (zutl/rest-result {:result "Submitted"} 202)
    (catch MissingSessionException _
      (zutl/rest-error "Missing session UUID header." 412))
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch Exception e
      (log/error e (str "Error processing AGD data: " (ZorkaUtil/hex data) (String. ^bytes data "UTF-8")))
      (zutl/rest-error "Internal error." 500))))


(defn submit-trc [{:keys [obj-store tstore]} agent-id session-uuid trace-uuid data]
  (try
    ; TODO weryfikacja argumentów
    (if-let [agent (zobj/get-obj obj-store {:class :host, :id agent-id})]
      (do
        (.handleTraceData tstore agent-id session-uuid trace-uuid data
                          (doto (ChunkMetadata.)
                            (.setAppId (:app agent))
                            (.setEnvId (:env agent))))
        (zutl/rest-result {:result "Submitted"} 202))
      (zutl/rest-error "No such agent." 401))
    ; TODO :status 507 jeżeli wystąpił I/O error (brakuje miejsca), agent może zareagować tymczasowo blokujący wysyłki
    (catch MissingSessionException _
      (zutl/rest-error "Missing session UUID header." 412))
    (catch Exception e
      (log/error e "Error processing TRC data: " (ZorkaUtil/hex data))
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
            (into {} (for [{:keys [name id flags] :as r} (zobj/find-and-get obj-store {:class :ttype})]
                       {name {:tid id, :id id, :flags flags}}))))
        (when-not (get @data tn)
          (let [{:keys [id flags] :as tt} (zobj/put-obj
                     obj-store
                     {:class   :ttype,
                      :name tn,
                      :descr    (str "Trace type: " tn),
                      :glyph   "awe/cube",
                      :flags   1,
                      :comment "Auto-registered. Please edit."})
                ti {:tid id, :id id, :flags flags}]
            (swap! data assoc tn ti)))
        (get @data tn)))))


(defn trace-id-translator [obj-store]
  (let [data (atom {})]
    (reify
      TraceTypeResolver
      (resolve [_ tn]
        (let [{:keys [tid id flags] :as tr} (trace-id-translate obj-store data tn)]
          (when (= 0 (bit-and flags 0x08))
            (log/info "Marking trace type" id "as used.")
            (let [rec (zobj/get-obj obj-store {:class :ttype, :id id})
                  flags (bit-or (:flags rec) 0x08)]
              (zobj/put-obj obj-store (assoc rec :flags flags))
              (swap! data assoc tn (assoc tr :flags flags))))
          tid)))))


(defn open-tstore [new-conf old-conf old-store obj-store]
  (let [old-root (when (:path old-conf) (File. ^String (:path old-conf))),
        idx-cache (if old-store (.getIndexerCache old-store) (HashMap.))
        new-root (File. ^String (:path new-conf)), new-props (zutl/conf-to-props new-conf "store.")]
    (when-not (.exists new-root) (.mkdirs new-root))        ; TODO handle errors here, check if directory is writable etc.
    (cond
      (or (nil? old-store) (not (= old-root new-root)))
      (let [store (RotatingTraceStore. new-root new-props (trace-id-translator obj-store) idx-cache)]
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
    (let [tstore (open-tstore new-conf old-conf tstore obj-store)
          nmt (doall (for [n (range (:maint-threads new-conf 2))]
                       (ZicoMaintThread. (str n) (* 1000 (:maint-interval new-conf 10)) tstore)))
          postproc (TemplatingMetadataProcessor.)
          trace-descs {}                                       ; TODO (into {} (for [obj (zobj/find-and-get obj-store {:class :ttype})] {(:id obj), (:descr obj)}))
          ]
      ; Set up template descriptions for rendering top level DESC field;
      (doseq [[id descr] trace-descs :when id :when descr :let [id id]]
        (.putTemplate postproc id descr))
      ; Stop old maintenance threads (new ones are already started)
      (doseq [t maint-threads]
        (.stop t))
      (.setPostproc tstore postproc)
      (assoc app-state
        :maint-threads nmt
        :tstore tstore
        :trace-postproc postproc))
    app-state))

