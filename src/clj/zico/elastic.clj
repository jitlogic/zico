(ns zico.elastic
  "Simple API to Elastic Search."
  (:require
    [zico.util :as zu]
    [zico.trace :as zt]
    [clojure.string :refer [join]]
    [clojure.data.json :as json]
    [clj-http.client :as http]
    [clojure.string :as cstr]
    [clojure.tools.logging :as log]
    [slingshot.slingshot :refer [throw+ try+]])
  (:import
    (com.jitlogic.zorka.common.collector TraceChunkData TraceChunkStore Collector)
    (java.util.regex Pattern)
    (com.jitlogic.zorka.common.cbor TraceRecordFlags)))

(def TYPE-CHUNK 3)

(def DOC-MAPPING-PROPS
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
   :fulltext {:type :text}
   :tdata {:type :binary, :index false}
   :sdata {:type :binary, :index false}})

(def DOC-MAPPING-TEMPLATES
  [{:attrs_as_strings
    {:match   "attrs.*"
     :mapping {:type :keyword}}}])


(defn parse-response [resp]
  (when (string? (:body resp))
    (json/read-str (:body resp))))


(defn- index-headers [{:keys [username password] :as db} tsnum]
  (merge
    {:accept "application/json", :content-type "application/json"}
    (when (string? password)
      {:authorization (str "Basic " (zu/b64enc (.getBytes (str username ":" password))))})))


(defn checked-req [req {:keys [body status] :as resp}]
  (when-not (<= 200 status 299)
    (cond
      (nil? body)
      (do
        (log/error "Error occured in elastic request: req:" req "resp: " resp)
        (throw+ {:type :unknown, :req req, :resp resp, :status status}))
      (.contains body "Limit of total fields")
      (throw+ {:type :field-limit-exceeded, :req req, :resp resp, :status status})
      :else
      (do
        (log/error "Error occured in elastic request: req:" req "resp: " resp)
        (throw+ {:type :other, :req req, :resp resp, :status status}))))
  resp)

(def RE-INDEX-NAME #"([\w^_]+)_([0-9a-fA-F]{6})")


(defn index-name [db tsnum]
  (format "%s_%06x" (:name db) tsnum))


(defn index-parse [s]
  (when-let [[_ name tsnum] (re-matches RE-INDEX-NAME (str s))]
    {:name name, :tsnum (Long/parseLong tsnum 16)}))


(defn- elastic [http-method db tsnum & {:keys [path body verbose?]}]
  (let [req (merge
              {:headers (index-headers db tsnum)
               :unexceptional-status (constantly true)
               :connection-manager (:connection-manager db)}
              (when (map? body) {:body (json/write-str body)})
              (when (string? body) {:body body}))
        idx-name (if (number? tsnum)
                   (format "%s/%s" (:url db) (index-name db tsnum))
                   (format "%s/%s_*" (:url db) (:name db "zico")))
        url (apply str idx-name path)]
    (when verbose? (log/info "elastic: tsnum:" tsnum "url:" url "req:" req))
    (->>
      (http-method url req)
      (checked-req req)
      parse-response
      zu/keywordize)))


(defn list-data-indexes [db]
  "List indexes matching `mask` in database `db`"
  (let [mask (Pattern/compile (str "^" (:name db) "_([a-zA-Z0-9]+)$"))]
    (sort-by
      :tsnum
      (for [ix (-> (http/get
                     (str (:url db) "/_cat/indices")
                     {:headers (index-headers db 0), :connection-manager (:connection-manager db)})
                   parse-response)
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

(defonce ATTR-KEY-TRANSFORMS [])


(defn attr-key-transform [rules attr]
  (if attr
    (or
      (first
        (for [{:keys [match replace]} rules :let [r (re-matches match attr)] :when r]
          (if (string? r)
            replace
            (-> replace
                (.replace "$1" (nth r 1 ""))
                (.replace "$2" (nth r 2 ""))
                (.replace "$3" (nth r 3 ""))
                (.replace "$4" (nth r 4 ""))))))
      attr)
    attr))


(defn str->akey [s]
  (let [rslt
        (str "attrs."
             (attr-key-transform
               ATTR-KEY-TRANSFORMS
               (-> s
                   (.replaceAll "[\\/\\*\\?\"<>\\| \n\t\r,\\:]" "_")
                   (.replaceAll "^[_\\.]" "")
                   (.replaceAll "\\.(\\d)" "_$1")
                   (.replace \. \_)
                   .toLowerCase)))]
    (if (> (.length rslt) 255) (.substring rslt 0 255) rslt)))


(defn enable-field-mapping [app-state tsnum fields]
  (let [fm (into {} (for [f fields] {(str->akey f) {:type "text", :fielddata true}}))
        rslt (elastic http/put (-> app-state :conf :tstore) tsnum
                      :path ["/_mapping"]
                      :body {:properties fm})]
    rslt))


(defn index-create [{:keys [flattened-attrs] :as db} tsnum]
  (elastic
    http/put db tsnum
    :body {:settings (merge INDEX-SETTINGS-DEFAUTLS (select-keys db INDEX-SETTINGS-KEYS))
           :mappings (merge
                       {:_source {:excludes [:fulltext]}
                        :properties (merge DOC-MAPPING-PROPS (when flattened-attrs {:attrs {:type :flattened}}))}
                       (when-not flattened-attrs {:dynamic_templates DOC-MAPPING-TEMPLATES}))}))


(defn index-delete [db tsnum]
  (elastic http/delete db tsnum))


(defn index-refresh [db tsnum]
  (elastic http/get db tsnum :path ["/_refresh"]))


(defn tcd->doc [^TraceChunkData tcd flattened-attrs]
  (zu/without-nil-vals
    (merge
      {:doctype  TYPE-CHUNK
       :traceid  (.getTraceIdHex tcd)
       :spanid   (.getSpanIdHex tcd)
       :chnum    (.getChunkNum tcd)
       :tst      (.getTstamp tcd)
       :tstamp   (.getTstamp tcd)
       :error    (.hasFlag tcd TraceRecordFlags/TF_ERROR_MARK)
       :duration (- (.getTstop tcd) (.getTstart tcd))
       :klass    (.getKlass tcd)
       :method   (.getMethod tcd)
       :recs     (.getRecs tcd)
       :calls    (.getCalls tcd)
       :errors   (.getErrors tcd)
       :ttype    (.getTtype tcd)
       :tdata    (zu/b64enc (.getTraceData tcd))
       :sdata    (zu/b64enc (.getSymbolData tcd))
       :fulltext (str (.getKlass tcd) "." (.getMethod tcd) " "
                      (when (.getAttrs tcd) (cstr/join " " (.values (.getAttrs tcd)))))}
      (if (.getParentIdHex tcd)
        {:parentid (.getParentIdHex tcd), :top-level false}
        {:top-level true})
      (if flattened-attrs
        {:attrs
         (zu/group-map
           (for [[k v] (.getAttrs tcd) :let [f (.substring (str->akey k) 6)]]
             [f (str (.replace v \tab \space) \tab (.replace k \tab \space))]))}
        (zu/group-map
          (for [[k v] (.getAttrs tcd) :let [f (str->akey k)]]
            [f (str (.replace v \tab \space) \tab (.replace k \tab \space))]))))))


(declare next-active-index)


(defn chunk-add [app-state doc retry]
  (let [db (-> app-state :conf :tstore)]
    (try+
      (elastic http/post db @(:tstore-tsnum app-state) :path ["/_doc"] :body doc)
      (catch [:type :field-limit-exceeded] _
        (log/warn
          "Detected :field-limit-exceeded error. Index will be rotated. You can either increase fields limit in zico.edn"
          "or define some additional attribute transform rules (check index" @(:tstore-tsnum app-state) "for redundant attribute names")
        (when retry
          (locking (-> app-state :tstore-lock)
            (try+
              (elastic http/post db @(:tstore-tsnum app-state) :path ["/_doc"] :body doc)
              (catch [:type :field-limit-exceeded] _
                (next-active-index app-state)
                (elastic http/post db @(:tstore-tsnum app-state) :path ["/_doc"] :body doc)))
            ))))))


(defn chunk-store [{{{:keys [flattened-attrs]} :tstore} :conf :as app-state}]
  "Returns trace chunk store with Elastic Search backend."
  (reify
    TraceChunkStore
    (add [_ tcd]
      (chunk-add app-state (tcd->doc tcd flattened-attrs) true))
    (addAll [_ tcds]
      (doseq [tcd tcds]
        (chunk-add app-state (tcd->doc tcd flattened-attrs) true)))))


(defn index-stats [app-state tsnum]
  (elastic
    http/get (-> app-state :conf :tstore) tsnum
    :path [ "/_stats"]))


(defn index-size [app-state tsnum]
  (-> (index-stats app-state tsnum)
      :indices first second :total :store :size_in_bytes))


(defn next-active-index [app-state]
  (let [tsnum @(:tstore-tsnum app-state),
        new-tsnum (inc tsnum),
        conf (-> app-state :conf :tstore),]
    (log/info "Rotating trace store. tsnum: " tsnum "->" new-tsnum)
    (index-create conf new-tsnum)
    (when-not (-> app-state :conf :tstore :flattened-attrs)
      (enable-field-mapping app-state new-tsnum (map :attr (-> app-state :conf :filter-defs))))
    (reset! (:tstore-tsnum app-state) new-tsnum)
    (future
      (Thread/sleep (* 1000 (:post-merge-pause conf 10)))
      (log/info "Running final index merge ...")
      (merge-index conf tsnum (:final-merge-segments conf 1))
      (log/info "Finished final index merge ..."))
    (log/info "Current active index is" new-tsnum)))


(defn rotate-index [app-state]
  (if (= :elastic (-> app-state :conf :tstore :type))
    (do
      (next-active-index app-state)
      "Rotation successful.")
    "Rotation request ignored."))


(defn delete-old-indexes [app-state max-count]
  (let [conf (-> app-state :conf :tstore),
        indexes (sort-by :tsnum (list-data-indexes conf))
        rmc (- (count indexes) max-count)]
    (when (> rmc 0)
      (let [rmi (take rmc indexes)]
        (doseq [i rmi :let [tsn (:tsnum i)]]
          (log/info "Removing index" tsn)
          (index-delete conf (:tsnum i))
          )))))


(defn check-rotate [app-state]
  (locking (:tstore-lock app-state)
    (let [conf (-> app-state :conf :tstore),
          max-size (* 1024 1024 (+ (:index-size conf) (:index-overcommit conf))),
          tsnum @(:tstore-tsnum app-state)
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
             (for [[k v] attr-matches] {:match {(str->akey k) (str v "\t" k)}})
             )}}})


(def RSLT-FIELDS [:traceid :spanid :parentid :ttype :tstamp :duration :calls :errors :recs
                  :klass :method :top-level])

(def RE-ATTRF #"attrs\.(.*)")
(def RE-ATTRV #"(.*)\t(.*)")


(defn doc->rest [{:keys [tstamp] :as doc} & {:keys [chunks? index flattened-attrs]}]
  (merge
    (assoc
      (select-keys doc RSLT-FIELDS)
      :tstamp (zu/millis->iso-time tstamp)
      :tst tstamp
      :attrs
      (if flattened-attrs
        (into {}
          (apply concat
            (for [[_ v] (:attrs doc)
                  :let [vs (if (vector? v) v [v])]
                  v vs :let [[_ s a] (re-matches RE-ATTRV v)]
                  :when (and (string? s) (string? a))]
              {a s})))
        (into {}
          (apply concat
            (for [[k v] doc :when (re-matches RE-ATTRF (name k))
                  :let [vs (if (vector? v) v [v])]
                  v vs :let [[_ s a] (re-matches RE-ATTRV v)]
                  :when (and (string? s) (string? a))]
              {a s})))))
    (when chunks? {:tdata (:tdata doc), :sdata (:sdata doc)})
    {:tsnum (:tsnum (index-parse index))}))


(defmethod zt/attr-vals :elastic [app-state attr]
  (let [body {:size 0, :aggregations {:avals {:terms {:field (str->akey attr)}}}}
        rslt (elastic http/get (-> app-state :conf :tstore) nil
                      :path ["/_search"] :body body)]
    (for [b (-> rslt :aggregations :avals :buckets)
          :let [ak (get b "key")] :when (not= ak attr)]
      (first (cstr/split ak #"\t")))))


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


(defmethod zt/trace-search :elastic [{{{:keys [flattened-attrs]} :tstore} :conf :as app-state} query {:keys [chunks? raw? spans-only?]}]
  (let [body (q->e query),
        rfn (if raw? zt/rest->tcd identity), ; TODO wyeliminować podwójne mapowanie wyniku
        _source (clojure.string/join "," (map name RSLT-FIELDS))
        rslt (elastic http/get (-> app-state :conf :tstore) nil
                      :path ["/_search?_source=" _source ",attrs" (if flattened-attrs "" ".*")
                             (if chunks? ",tdata,sdata" "")]
                      :body body)]
    (for [doc (-> rslt :hits :hits) :let [index (get doc "_index"), doc (:_source (zu/keywordize doc))]]
      (rfn (doc->rest doc :chunks? chunks?, :index index, :flattened-attrs flattened-attrs)))))


(defmethod zt/new-trace-store :elastic [{{conf :tstore} :conf :as app-state} old-state]
  (let [tstore-lock (:tstore-lock app-state)]
    (locking tstore-lock
      (let [indexes (list-data-indexes conf)
            tsnum (if (empty? indexes) 0 (apply max (map :tsnum indexes)))
            store (chunk-store app-state)
            ;TBD task-queue (or (:task-queue app-state) (ArrayBlockingQueue. writer-queue))
            ;TBD executor (or (:executor app-state) (ThreadPoolExecutor. writer-threads writer-threads 30000 TimeUnit/MILLISECONDS task-queue))
            collector (Collector. store false)]
        (log/info "Collector will write to index" tsnum)
        (reset! (:tstore-tsnum app-state) tsnum)
        (when (empty? indexes)
          (index-create conf tsnum)
          (when-not (-> app-state :conf :tstore :flattened-attrs) ; apply mappings for field transforms
            (enable-field-mapping app-state tsnum (map :attr (-> app-state :conf :filter-defs)))))
        {:collector collector, :store store}))))


(defn elastic-health [app-state]
  (try
    (let [ixs (sort-by :tsnum (list-data-indexes (-> app-state :conf :tstore)))]
      (and
        (not (empty? ixs))
        (#{:green :yellow} (:health (last ixs)))))
    (catch Exception e
      (log/error "/healthz check returned :DOWN"))))

