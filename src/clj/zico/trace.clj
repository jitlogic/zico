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
      TraceRecord RecursiveTraceDataRetriever ObjectRef StackData TraceTypeResolver)
    (java.util HashMap Map List ArrayList)
    (io.zorka.tdb.meta ChunkMetadata StructuredTextIndex)
    (io.zorka.tdb MissingSessionException)
    (io.zorka.tdb.search.rslt SearchResult)
    (io.zorka.tdb.search SearchQuery SortOrder QmiNode)
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
  (let [min-duration (:min-duration q)
        max-duration (:max-duration q)]
    (doto (QmiNode.)
      (.setAppId (zobj/extract-uuid-seq (:app q)))
      (.setEnvId (zobj/extract-uuid-seq (:env q)))
      (.setTypeId (zobj/extract-uuid-seq (:ttype q)))
      (.setMinDuration (if min-duration (* min-duration 15259) 0)) ; ticks to secs: 1000000/65536 = 15259
      (.setMaxDuration (if max-duration (* max-duration 15259) Long/MAX_VALUE))
      (.setMinCalls (:min-calls q 0))
      (.setMaxCalls (:max-calls q Long/MAX_VALUE))
      (.setMinErrs (:min-errors q 0))
      (.setMaxErrs (:max-errors q Long/MAX_VALUE))
      (.setMinRecs (:min-recs q 0))
      (.setMaxRecs (:max-recs q Long/MAX_VALUE))
      (.setTstart (ctc/to-long (ctf/parse PARAM-FORMATTER (:tstart q "20100101T000000Z"))))
      (.setTstop (ctc/to-long (ctf/parse PARAM-FORMATTER (:tstop q "20300101T000000Z"))))
      )))

(def TYPES {"qmi" :qmi, "or" :or, "text" :text, "xtext" :xtext, "kv" :kv})


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
  (let [q (prep-search-node q)]
    (if (string? q)
      (TextNode. ^String q true true)
      (case (TYPES (:type q) (:type q))
        :and (AndExprNode. (lmap parse-search-node (:args q)))
        :or (OrExprNode. (lmap parse-search-node (:args q)))
        :text (TextNode. (:text q) (:match-start q false) (:match-end q false))
        :xtext (TextNode. (:text q) (:match-start q true) (:match-end q true))
        :qmi (parse-qmi-node q)
        :kv (KeyValSearchNode. (:key q) (parse-search-node (:val q)))
        nil))))


(defn parse-search-query [q]
  (doto (SearchQuery. (if (:q q) (parse-search-node (:q q)) (QmiNode.)))
    (.setLimit (:limit q 50))
    (.setOffset (:offset q 0))
    (.setAfter (:after q 0))
    (.setWindow (:window q 65536))
    (.setSortOrder (case (:sort-order q :none)
                     :none SortOrder/NONE,
                     :duration SortOrder/DURATION,
                     :calls SortOrder/CALLS,
                     :recs SortOrder/RECS,
                     :errors SortOrder/ERRORS))
    (.setSortReverse (:sort-reverse q false))
    (.setDeepSearch (:deep-search q true))))


(defn seq-search-results [^SearchResult tsr]
  (let [cid (.nextResult tsr)]
    (if (not= cid -1)
      (lazy-seq (cons cid (seq-search-results tsr)))
      nil)))


(defn get-uuids [uuid-fn dur-fn cid-sr limit]
  (loop [[cid & rst] cid-sr, rslt {}]
    (let [uuid (uuid-fn cid), dur (dur-fn cid)
          [cid2 dur2] (get rslt uuid)
          rslt (assoc rslt uuid [(if cid2 (max cid cid2) cid) (if dur2 (max dur dur2) dur)])]
      (cond
        (nil? rst) rslt
        (>= (count rslt) limit) rslt
        :else (recur rst rslt)))))


(defn find-and-map-by-id [obj-store fexpr]
  (into {} (for [uuid (zobj/find-obj obj-store fexpr)]
             {(zobj/extract-uuid-seq uuid) uuid})))


(defn trace-search
  [{:keys [obj-store trace-store] :as app-state}
   {{:keys [limit offset] :or {limit 50, offset 0} :as params} :data :as req}]
  (let [query (parse-search-query params)
        apps (find-and-map-by-id obj-store {:class :app})
        envs (find-and-map-by-id obj-store {:class :env})
        ttps (find-and-map-by-id obj-store {:class :ttype})
        cid-sr (seq-search-results (.search trace-store query))
        uuids (when cid-sr
                (get-uuids
                  #(.getTraceUUID trace-store %)
                  #(.getTraceDuration trace-store %) ; TODO extract whole chunk earlier and operate directly on it
                  cid-sr (* 8 (+ offset limit))))    ; TODO handle sort order, offset and limit
        uuids (for [[uuid [cid dur]] uuids] {:uuid uuid, :cid cid, :dur dur})
        data (reverse
               (sort-by :data-offs
                        (for [{:keys [uuid cid]} uuids
                              :let [cm (.getChunkMetadata trace-store cid)]]
                          {:uuid       uuid
                           :lcid       cid
                           :descr      (.getDesc trace-store cid)
                           :duration   (.getDuration cm)
                           :ttype      (ttps (.getTypeId cm))
                           :app        (apps (.getAppId cm))
                           :env        (envs (.getEnvId cm))
                           :tst        (.getTstamp cm)
                           :tstamp     (zutl/str-time-yymmdd-hhmmss-sss (* 1000 (.getTstamp cm)))
                           :data-offs  (.getDataOffs cm)
                           :start-offs (.getStartOffs cm)
                           :flags      (if (.hasFlag cm ChunkMetadata/TF_ERROR) #{:err} #{})
                           :recs       (.getRecs cm)
                           :calls      (.getCalls cm)
                           :errs       (.getErrors cm)
                           })))]
    {:status 200, :body {:type :rest, :data (vec (take limit (drop offset data)))}}))


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


(defn trace-detail [{:keys [trace-store obj-store] :as app-state} stack-limit ctx]
  (let [{:keys [uuid]} (-> ctx :params)
        cids (.getChunkIds trace-store uuid)
        lcid (.get cids (- (.size cids) 1))
        cm (.getChunkMetadata trace-store lcid)
        rtr (doto (RecursiveTraceDataRetriever. (trace-record-filter obj-store)) (.setStackLimit stack-limit))
        rslt (.retrieve trace-store uuid rtr)]
    {:status 200, :body {:type :rest, :data (merge {:uuid uuid} rslt)}}))


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
      (log/error e "Internal error when processing submitted agent data.")
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
      (log/error e "Internal error when processing submitted agent data.")
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
            (into {} (for [r (zobj/find-and-get obj-store {:class :ttype})]
                       {(:name r) (zobj/extract-uuid-seq (:uuid r))}))))
        (when-not (get @data tn)
          (let [tt (zobj/put-obj
                     obj-store
                     {:class   :ttype, :name tn,
                      :descr    (str "Trace type: " tn),
                      :glyph   "awe/cube",
                      :comment "Auto-registered. Please edit."})
                ti (zobj/extract-uuid-seq (:uuid tt))]
            (swap! data assoc tn ti)))
        (get @data tn)))))


(defn trace-id-translator [obj-store]
  (let [data (atom {})]
    (reify
      TraceTypeResolver
      (resolve [_ tn]
        (trace-id-translate obj-store data tn)))))


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

