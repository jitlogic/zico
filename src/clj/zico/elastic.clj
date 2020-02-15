(ns zico.elastic
  "Simple API to Elastic Search."
  (:require
    [zico.util :as zu]
    [clojure.string :refer [join]]
    [clojure.data.json :as json]
    [clj-http.client :as http]
    [clojure.set :as cs]
    [clojure.tools.logging :as log])
  (:import
    (java.util HashMap Set Map ArrayList Collection)
    (com.jitlogic.zorka.common.collector SymbolResolver SymbolMapper TraceChunkData TraceChunkStore Collector
                                         TraceDataExtractor TraceStatsExtractor CachingSymbolMapper)
    (java.util.regex Pattern)
    (com.jitlogic.zorka.common.cbor TraceRecordFlags)
    (java.time LocalDateTime OffsetDateTime)))

(def TYPE-SYMBOL 1)
(def TYPE-METHOD 2)
(def TYPE-CHUNK 3)
(def TYPE-SEQ 4)

(def DOC-MAPPING-PROPS                                      ; TODO wyrównac ten schemat z zico.schema.tdb/ChunkMetadata
  {:doctype {:type :long}
   :traceid {:type :keyword}
   :spanid {:type :keyword}
   :parentid {:type :keyword}
   :chnum {:type :long}
   :tst {:type :long}
   :tstamp {:type :date}
   ; :desc is not saved in elasticsearch
   :error {:type :boolean}
   :top-level {:type :boolean}
   :duration {:type :long}
   :klass {:type :keyword}
   :method {:type :keyword}
   :recs {:type :long}
   :calls {:type :long}
   :errors {:type :long}
   :ttype {:type :keyword}

   :tdata {:type :binary, :index false}
   :mids {:type :long}

   ; symbol registries and sequence generators
   :mdesc {:type :keyword}
   :symbol {:type :keyword}
   :seq {:type :long}
   })

(def DOC-MAPPING-TEMPLATES
  [{:attrs_as_strings
    {:match   "attrs.*"
     :mapping {:type :text}}}])

(def DOC-MAPPINGS
  {:properties DOC-MAPPING-PROPS
   :dynamic_templates DOC-MAPPING-TEMPLATES
   })

(defn parse-response [resp]
  (when (string? (:body resp))
    (json/read-str (:body resp))))

(defn- index-headers [{:keys [username password] :as db} tsnum]
  (merge
    {:accept "application/json", :content-type "application/json"}
    (when (string? password)
      {:authorization (str "Basic " (zu/b64enc (.getBytes (str username ":" password))))})))

(defn checked-req [req resp]
  (when-not (<= 200 (:status resp) 299)
    (log/error "Error occured in elastic request: req:" req "resp: " resp)
    (throw (ex-info (str "Error calling elastic: " (:status resp) ": " (:body resp)) {:req req :resp resp})))
  resp)

(defn- elastic [http-method db tsnum & {:keys [path body verbose?]}]
  (let [req (merge
              {:headers (index-headers db tsnum)
               :unexceptional-status (constantly true)}
              (when (map? body) {:body (json/write-str body)})
              (when (string? body) {:body body}))
        idx-name (if (number? tsnum) (format "%s/%s_%06x" (:url db) (:name db) tsnum)
                                     (format "%s/%s_*" (:url db) (:name db)))
        url (apply str idx-name path)]
    (when verbose? (log/info "elastic: tsnum:" tsnum "url:" url "req:" req))
    (->>
      (http-method url req)
      (checked-req req)
      parse-response
      zu/keywordize)))

(defn list-indexes [db]
  "List indexes matching `mask` in database `db`"
  (let [mask (Pattern/compile (str "^" (:name db) "_([a-zA-Z0-9]+)$"))]
    (sort-by
      :tsnum
      (for [ix (-> (http/get (str (:url db) "/_cat/indices") {:headers (index-headers db 0)}) parse-response)
            :let [ix (zu/keywordize ix), xname (:index ix),
                  status (keyword (:status ix)), health (keyword (:health ix))
                  m (when (string? xname) (re-matches mask xname))]
            :when m]
        (assoc ix
          :status status :health health,
          :tsnum (Long/parseLong (second m) 16)
          :size (zu/size->bytes (:store.size ix)))
        ))))

(defn merge-index [db tsnum num-segments]
  (elastic
    http/post db tsnum
    :path [(format "/_forcemerge?max_num_segments=%d" num-segments)]))

(def INDEX-SETTINGS-DEFAUTLS
  {:number_of_shards   1
   :number_of_replicas 0
   :index.mapping.total_fields.limit 16384})

(def INDEX-SETTINGS-KEYS (keys INDEX-SETTINGS-DEFAUTLS))

(defn str->akey [s]
  (let [rslt
        (str "attrs."
             (-> s
                 (.replaceAll "[\\/\\*\\?\"<>\\| \n\t\r,\\:]" "_")
                 (.replaceAll "^[_\\.]" "")
                 (.replaceAll "\\.(\\d)" "_$1")
                 .toLowerCase))]
    (if (> (.length rslt) 255) (.substring rslt 0 255) rslt)))

(defn enable-field-mapping [app-state fields]
  (let [fm (into {} (for [f fields] {(str->akey f) {:type "text", :fielddata true}}))
        rslt (elastic http/put (-> app-state :conf :tstore) nil
                      :path ["/_mapping"]
                      :body {:properties fm})]
    rslt))

(defn index-create [db tsnum]
  (elastic
    http/put db tsnum
    :body {:settings (merge INDEX-SETTINGS-DEFAUTLS (select-keys db INDEX-SETTINGS-KEYS))
           :mappings DOC-MAPPINGS}))

(defn index-delete [db tsnum]
  (elastic http/delete db tsnum))

(defn index-refresh [db tsnum]
  (elastic http/get db tsnum :path ["/_refresh"]))

(defn seq-next [db tsnum seq-name block-sz seq-quant]
  (let [idx (format "%s_%06x" (:name db) tsnum)
        data (for [_ (range 0 block-sz seq-quant)]
               [{:update {:_index idx, :_id (str "SEQ." (name seq-name)), :retry_on_conflict 8}}
                {:script {:source (format "ctx._source.seq += %d" seq-quant)}
                 :upsert {:seq 1, :type TYPE-SEQ}}])
        body (str (join "\n" (map json/write-str (apply concat data))) "\n")]
    (take
      block-sz
      (apply concat
        (for [r (:items (elastic http/post db tsnum :path ["/_bulk?refresh=true&_source=true"] :body body))
              :let [n (get-in r ["update" "get" "_source" "seq"])]]
          (range n (+ n seq-quant)))))))

(def SYMS-ADD-LOCK (Object.))

(defn syms-add [db tsnum syms]
  (if (empty? syms)
    {}
    (locking SYMS-ADD-LOCK
      ; TODO dual locking here (check if symbols were added before lock)
      (let [idx (format "%s_%06x" (:name db) tsnum)
            rslt (zipmap syms (seq-next db tsnum :SYMBOLS (count syms) 4))
            data (for [[s i] rslt]
                   [{:index {:_index idx, :_id (str "SYM." i)}}
                    {:doctype TYPE-SYMBOL, :symbol s}])
            body (str (join "\n" (map json/write-str (apply concat data))) "\n")]
        (elastic
          http/post db tsnum
          :path ["/_bulk?refresh=true"]
          :body body)                                       ; TODO sprawdzic czy poprawnie sie dodaly
        rslt))))

(defn syms-resolve [db tsnum sids]
  (if (empty? sids)
    {}
    (let [docs (elastic http/get db tsnum :path ["/_mget"]
                        :body {:docs (for [s sids] {:_id (str "SYM." s)})})]
      (into {}
        (for [d (:docs docs)]
          {(Long/parseLong (.substring ^String (get d "_id") 4))
           (get-in d ["_source" "symbol"])})))))

(defn syms-search [db tsnum syms]
  (if (empty? syms)
    {}
    (let [idx (format "%s_%06x" (:name db) tsnum)
          data (for [s syms]
                 [{:index idx}
                  {:query {:term {:symbol s}}}])
          body (str (join "\n" (map json/write-str (apply concat data))) "\n")
          rslt (elastic http/get db tsnum :path ["/_msearch"] :body body)]
      (into {}
        (for [r (:responses rslt) :let [h (first (get-in r ["hits" "hits"]))] :when h]
          {(get-in h ["_source" "symbol"]) (Long/parseLong (.substring ^String (get h "_id") 4))})))))

(defn syms-map [db tsnum m]
  "Given agent-side symbol map (aid -> name), produce agent-collector mapping (aid -> rid)"
  (let [syms (set (vals m)),
        rsmap (syms-search db tsnum syms),
        asyms (cs/difference syms (keys rsmap)),
        asmap (syms-add db tsnum asyms)]
    (into {}
      (for [[aid sym] m :let [rid (or (rsmap sym) (asmap sym))] :when rid]
        {aid rid}))))

(defn mids-add [db tsnum mdescs]
  (if (empty? mdescs)
    {}
    (let [idx (format "%s_%06x" (:name db) tsnum),
          rslt (zipmap mdescs (seq-next db tsnum :METHODS (count mdescs) 2))
          data (for [[[c m s] i] rslt]
                 [{:index {:_index idx, :_id (str "MID." i)}}
                  {:doctype TYPE-METHOD, :mdesc (str c "," m "," s)}])
          body (str (join "\n" (map json/write-str (apply concat data))) "\n")]
      (elastic
        http/post db tsnum :path ["/_bulk?refresh=true"], :body body)
      rslt)))

(defn mids-resolve [db tsnum mids]
  (if (empty? mids)
    {}
    (let [docs (elastic http/get db tsnum :path ["/_mget"]
                        :body {:docs (for [m mids] {:_id (str "MID." m)})})]
      (into {}
        (for [d (:docs docs) :let [mdesc (get-in d ["_source" "mdesc"])] :when mdesc]
          {(Long/parseLong (.substring ^String (get d "_id") 4))
           (vec (for [i (.split mdesc ",")] (Long/parseLong i)))})))))

(defn mids-search [db tsnum mdescs]
  (if (empty? mdescs)
    {}
    (let [idx (format "%s_%06x" (:name db) tsnum),
          data (for [[c m s] mdescs :let [md (str c "," m "," s)]]
                 [{:index idx} {:query {:term {:mdesc md}}}])
          body (str (join "\n" (map json/write-str (apply concat data))) "\n")
          rslt (elastic http/get db tsnum :path ["/_msearch"] :body body)]
      (into {}
        (for [r (:responses rslt) :let [h (first (get-in r ["hits" "hits"]))] :when h]
          {(vec (for [i (.split (get-in h ["_source" "mdesc"]) ",")]
                  (if (re-matches #"\d+" i) (Long/parseLong i) 0)))
           (Long/parseLong (.substring ^String (get h "_id") 4))})))))

(defn mids-map [db tsnum m]
  "Given agent-side mid map (aid -> [c,m,s]), produce agent-collector mapping (aid -> rid)"
  (let [mdefs (set (vals m)),
        rsmap (mids-search db tsnum mdefs),
        amids (cs/difference mdefs (keys rsmap)),
        asmap (mids-add db tsnum amids)]
    (into {}
      (for [[aid mdef] m :let [rid (or (rsmap mdef) (asmap mdef))] :when rid]
        {aid rid}))))

(defn methods-resolve [db tsnum mids]
  (let [mdss (mids-resolve db tsnum mids)
        sids (into #{} (flatten (vals mdss)))
        syms (syms-resolve db tsnum sids)]
    (into {}
      (for [[i [c m s]] mdss :let [cs (syms c), ms (syms m)] :when (and cs ms)]
        {i (str cs "." ms "()")}))))

(defn symbol-resolver [db]
  "Returns SymbolResolver with Elastic Search as backend."
  (reify
    SymbolResolver
    (^Map resolveSymbols [_ ^Set sids ^int tsnum]
      (let [rslt (HashMap.)]
        (doseq [[sid sym] (syms-resolve db tsnum (seq sids))]
          (.put rslt (.intValue sid) sym))
        rslt))
    (^Map resolveMethods [_ ^Set mids ^int tsnum]
      (let [rslt (HashMap.)]
        (doseq [[i s] (methods-resolve db tsnum (seq mids))]
          (.put rslt (.intValue i) s))
        rslt))))

(defn mdef->vec [md]
  [(.longValue (.getClassId md))
   (.longValue (.getMethodId md))
   (.longValue (.getSignatureId md))])

(defn symbol-mapper [db tsnum]
  "Returns SymbolMapper with Elastic Search as backend."
  (reify
    SymbolMapper
    (^Map newSymbols [_ ^Map m]
      (let [rslt (HashMap.)]
        (doseq [[aid rid] (syms-map db tsnum (into {} m))]
          (.put rslt (.intValue aid) (.intValue rid)))
        rslt))
    (^Map newMethods [_ ^Map m]
      (let [rslt (HashMap.),
            mdefs (into {} (for [[k v] m] {k, (mdef->vec v)}))
            mimap (mids-map db tsnum mdefs)]
        (doseq [[aid rid] mimap]
          (.put rslt (.intValue aid) (.intValue rid)))
        rslt))))

(defn millis->date [l]
  l)


(defn tcd->doc [^TraceChunkData tcd]
  (zu/without-nil-vals
    (merge
      {:doctype  TYPE-CHUNK
       :traceid  (.getTraceIdHex tcd)
       :spanid   (.getSpanIdHex tcd)
       :chnum    (.getChunkNum tcd)
       :tst      (.getTstamp tcd)
       :tstamp   (millis->date (.getTstamp tcd))
       :error    (.hasFlag tcd TraceRecordFlags/TF_ERROR_MARK)
       :duration (- (.getTstop tcd) (.getTstart tcd))
       :klass    (.getKlass tcd)
       :method   (.getMethod tcd)
       :recs     (.getRecs tcd)
       :calls    (.getCalls tcd)
       :errors   (.getErrors tcd)
       :ttype    (.getTtype tcd)
       :tdata    (zu/b64enc (.getTraceData tcd))  ; TODO tutaj kompresja
       :mids     (seq (.getMethods tcd))}
      (if (.getParentIdHex tcd)
        {:parentid (.getParentIdHex tcd), :top-level false}
        {:top-level true})
      (into {}
        (for [[k v] (.getAttrs tcd) :let [f (str->akey k) ]]
          {f (str (.replace v \tab \space) \tab (.replace k \tab \space))})))))

(defn chunk-add [db tsnum doc]
  (elastic http/post db tsnum :path ["/_doc"] :body doc))

(defn chunk-store [db tsnum]
  "Returns trace chunk store with Elastic Search backend."
  (reify
    TraceChunkStore
    (add [_ tcd]
      (chunk-add db tsnum (tcd->doc tcd)))
    (addAll [_ tcds]
      (doseq [tcd tcds]
        (chunk-add db tsnum (tcd->doc tcd))))))

(defn index-stats [app-state tsnum]
  (elastic
    http/get (-> app-state :conf :tstore) tsnum
    :path [ "/_stats"]))

(defn index-size [app-state tsnum]
  (-> (index-stats app-state tsnum)
      :indices first second :total :store :size_in_bytes))

(defn next-active-index [app-state]
  (let [tsnum (-> app-state :tstore :tsnum deref)
        new-tsnum (inc tsnum),
        conf (-> app-state :conf :tstore)
        mapper (CachingSymbolMapper. (symbol-mapper conf new-tsnum)),
        store (chunk-store conf new-tsnum)]
    (log/info "Rotating trace store. tsnum: " tsnum "->" new-tsnum)
    (index-create conf new-tsnum)
    (enable-field-mapping app-state (map :attr (-> app-state :conf :filter-defs)))
    (.reset ^Collector (-> app-state :tstore :collector) new-tsnum mapper store)
    (reset! (-> app-state :tstore :tsnum) new-tsnum)
    (Thread/sleep (* 1000 (:post-merge-pause conf 10)))
    (log/info "Running final index merge ...")
    (merge-index conf tsnum (:final-merge-segments conf 1))
    (log/info "Current active index is" new-tsnum)))

(defn rotate-index [app-state]
  (if (= :elastic (-> app-state :conf :tstore :type))
    (do
      (next-active-index app-state)
      "Rotation successful.")
    "Rotation request ignored."))

(defn delete-old-indexes [app-state max-count]
  (let [conf (-> app-state :conf :tstore),
        indexes (sort-by :tsnum (list-indexes conf))
        rmc (- (count indexes) max-count)]
    (when (> rmc 0)
      (let [rmi (take rmc indexes)]
        (doseq [i rmi :let [tsn (:tsnum i)]]
          (log/info "Removing index" tsn)
          (index-delete conf (:tsnum i))
          )))))

(defn check-rotate [app-state]
  (locking (-> app-state :tstore :collector)
    (let [conf (-> app-state :conf :tstore),
          max-size (* 1024 1024 (+ (:index-size conf) (:index-overcommit conf))),
          tsnum @(-> app-state :tstore :tsnum)
          cur-size (index-size app-state tsnum)]
      (log/debug (format "Active index utilization: %.02f" (* 100.0 (/ cur-size max-size))) "%")
      (when (> cur-size max-size)
        (log/debug "Pre-merging index " tsnum)
        (merge-index conf tsnum (:pre-merge-segments conf 4))
        (Thread/sleep (* 1000 (:post-merge-pause conf 10)))
        (when (> (index-size app-state tsnum) max-size)
          (next-active-index app-state)))
      (delete-old-indexes app-state (:index-count conf 16)))))

(defn q->e [{:keys [errors-only spans-only traceid spanid order-by order-dir
                    min-tstamp max-tstamp min-duration limit offset
                    attr-matches text]}]
  {:sort
         (if order-by
           {order-by {:order (or order-dir :desc)}}
           {:tstamp {:order :desc}})
   :from (or offset 0)
   :size (or limit 100)
   :query
         {:bool
          {:must
           (concat
             (filter
               some?
               [{:term {:doctype TYPE-CHUNK}}
                (when-not spans-only {:term {:top-level true}})
                (when traceid {:match {:traceid traceid}})
                (when spanid {:match {:spanid spanid}})
                (when min-duration {:range {:duration {:gte min-duration}}})
                (when-not (empty? text) {:query_string {:query text}})
                (when errors-only {:term {:error true}})
                (when (or min-tstamp max-tstamp)
                  {:range {:tstamp (into {}
                                     (when min-tstamp {:gte min-tstamp})
                                     (when max-tstamp {:lte max-tstamp}))}})])
             (for [[k v] attr-matches] {:match {(str->akey k) (str v)}})
             )}}})


(def RSLT-FIELDS [:traceid :spanid :parentid :ttype :tstamp :duration :calls :errors :recs
                  :klass :method :top-level])

(def RE-ATTRF #"attrs\.(.*)")
(def RE-ATTRV #"(.*)\t(.*)")

(def RE-INDEX-NAME #"([a-zA-Z0-9]+)_([0-9a-fA-F]+)")

(defn doc->rest [{:keys [tstamp] :as doc} & {:keys [chunks? index]}]
  (merge
    (assoc
      (select-keys doc RSLT-FIELDS)
      :tstamp (.toString (LocalDateTime/ofEpochSecond (/ tstamp 1000), 0, (.getOffset (OffsetDateTime/now))))
      :tst tstamp
      :attrs (into {}
               (for [[k v] doc :when (re-matches RE-ATTRF (name k))
                     :let [[_ s a] (re-matches RE-ATTRV v)]
                     :when (and (string? s) (string? a))]
                 {a s})))
    (when chunks? {:tdata (:tdata doc)})
    (when-let [[_ _ s] (when (string? index) (re-matches RE-INDEX-NAME index))]
      {:tsnum (Long/parseLong s 16)})))

(defn chunk->tcd [{:keys [traceid spanid parentid chnum tsnum tst duration klass method ttype recs calls errors tdata]}]
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
    tcd))

(defn attr-vals [app-state attr]
  (let [body {:size 0, :aggregations {:avals {:terms {:field (str->akey attr)}}}}
        rslt (elastic http/get (-> app-state :conf :tstore) nil
                      :path ["/_search"] :body body)]
    (for [b (-> rslt :aggregations :avals :buckets)
          :let [ak (get b "key")] :when (not= ak attr)]
      ak)))

(defn extract-props [prefix props]
  (for [[k v] props :let [k (zu/to-str k), vp (or (get v "properties") (get v :properties))]]
    (if vp
      (extract-props (str prefix "." k) vp)
      (str prefix "." k))))

(defn attr-names [app-state tsnum]
  (when (= :elastic (-> app-state :conf :tstore :type))
    (let [rslt (elastic http/get (-> app-state :conf :tstore) tsnum :path "/_mapping")
          attrs (flatten (for [[_ xdef] rslt] (extract-props "" (get-in xdef [:mappings :properties]))))]
      (sort (for [a attrs :when (.startsWith a ".attrs")] (.substring a 7))))))

(defn trace-search [app-state query & {:keys [chunks?]}]
  (let [body (q->e query)
        _source (clojure.string/join "," (map name RSLT-FIELDS))
        rslt (elastic http/get (-> app-state :conf :tstore) nil
                      :path ["/_search?_source=" _source ",attrs.*" (if chunks? ",tdata" "")]
                      :body body)]
    (for [doc (-> rslt :hits :hits) :let [index (get doc "_index"), doc (:_source (zu/keywordize doc))]]
      (doc->rest doc :chunks? chunks?, :index index))))

(defn trace-detail [{{:keys [search resolver]} :tstore :as app-state} traceid spanid]
  (let [chunks (search app-state {:traceid traceid, :spanid spanid, :spans-only true} :chunks? true)
        tex (TraceDataExtractor. resolver)
        rslt (.extract tex (ArrayList. ^Collection (map chunk->tcd chunks)))]
    rslt))

(defn trace-stats [{{:keys [search resolver]} :tstore :as app-state} traceid spanid]
  (let [chunks (search app-state {:traceid traceid :spanid spanid} :chunks? true)
        tex (TraceStatsExtractor. resolver)
        rslt (.extract tex (ArrayList. ^Collection (map chunk->tcd chunks)))]
    rslt))

(defn elastic-trace-store [app-state old-state]
  (let [new-conf (-> app-state :conf :tstore)
        state (or (:tstore old-state) {:tsnum (atom 0), :collector (Collector. 0 nil nil false)})
        collector (:collector state)]
    (locking collector
      (let [indexes (list-indexes new-conf)
            tsnum (if (empty? indexes) 0 (apply max (map :tsnum indexes)))]
        (reset! (:tsnum state) tsnum)
        (log/info "Collector will write to index" tsnum)
        (when (empty? indexes)
          (index-create new-conf tsnum)
          (enable-field-mapping app-state (map :attr (-> app-state :conf :filter-defs))))
        (.reset collector tsnum (CachingSymbolMapper. (symbol-mapper new-conf tsnum)) (chunk-store new-conf tsnum))))
    (assoc state
      :search trace-search, :detail trace-detail, :stats trace-stats, :attr-vals attr-vals
      :resolver (symbol-resolver new-conf))))
