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
    (java.util HashMap Set Map)
    (com.jitlogic.zorka.common.collector SymbolResolver SymbolMapper TraceChunkData TraceChunkStore Collector)
    (com.jitlogic.zorka.common.tracedata SymbolicMethod)
    (java.util.regex Pattern)
    (com.jitlogic.zorka.common.cbor TraceRecordFlags)
    (java.time LocalDateTime ZoneOffset)))

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
   :duration {:type :long}
   :klass {:type :keyword}
   :method {:type :keyword}
   :recs {:type :long}
   :calls {:type :long}
   :errors {:type :long}
   :ttype {:type :keyword}

   :tdata {:type :binary, :index false}
   :terms {:type :text}
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

(defn- index-headers [db tsnum]
  {:accept "application/json", :content-type "application/json"})

(defn checked-req [req resp]
  (when-not (<= 200 (:status resp) 299)
    (log/error "Error occured in elastic request: req:" req "resp: " resp)
    (throw (ex-info (str "Error calling elastic: " (:status resp) ": " (:body resp)) {:req req :resp resp})))
  resp)

(defn- elastic [http-method db tsnum & {:keys [path body]}]
  (let [req (merge
              {:headers (index-headers db tsnum)
               :unexceptional-status (constantly true)}
              (when (map? body) {:body (json/write-str body)})
              (when (string? body) {:body body}))
        url (apply str (format "%s/%s_%06x" (:url db) (:name db) tsnum) path)]
    (->>
      (http-method url req)
      (checked-req req)
      parse-response
      zu/keywordize)))

(defn list-indexes [db]
  "List indexes matching `mask` in database `db`"
  (let [mask (Pattern/compile (str "^" (:name db) "_([a-zA-Z0-9]+)$"))]
    (for [ix (-> (http/get (str (:url db) "/_cat/indices")
                           {:headers {:accept "application/json"}})
                 parse-response)
          :let [ix (zu/keywordize ix), xname (:index ix),
                status (keyword (:status ix)), health (keyword (:health ix))
                m (when (string? xname) (re-matches mask xname))]
          :when m]
      (assoc ix :status status :health health, :tsnum (Long/parseLong (second m) 16)))))

(def DEFAULT-INDEX-SETTINGS
  {:number_of_shards   1
   :number_of_replicas 0
   })

(defn index-create [db tsnum]
  (elastic
    http/put db tsnum
    :body {:settings (merge DEFAULT-INDEX-SETTINGS (:settings db))
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

(defn symbol-resolver [db tsnum]
  "Returns SymbolResolver with Elastic Search as backend."
  (reify
    SymbolResolver
    (^Map resolveSymbols [_ ^Set sids]
      (let [rslt (HashMap.)]
        (doseq [[sid sym] (syms-resolve db tsnum (seq sids))]
          (.put rslt (.intValue sid) sym))
        rslt))
    (^Map resolveMethods [_ ^Set mids]
      (let [rslt (HashMap.)]
        (doseq [[i s] (methods-resolve db tsnum (seq mids))]
          (.put rslt (.intValue i) s))
        rslt))))

(defn mdef->vec [md]
  [(.longValue (.getClassId md))
   (.longValue (.getMethodId md))
   (.longValue (.getSignatureId md))])

(defn vec->mdef [[c m s]]
  (SymbolicMethod. (.intValue c) (.intValue m) (.intValue s)))

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

(defn str->field [s]
  (let [rslt
        (str "attrs."
             (-> s
                 (.replaceAll "[\\/\\*\\?\"<>\\| \n\t\r,\\:]" "_")
                 (.replaceAll "^[_\\.]" "")
                 .toLowerCase))]
    (if (> (.length rslt) 255) (.substring rslt 0 255) rslt)))

(defn tcd->doc [^TraceChunkData tcd]
  (zu/without-nil-vals
    (merge
      {:doctype  TYPE-CHUNK
       :traceid  (.getTraceIdHex tcd)
       :spanid   (.getSpanIdHex tcd)
       :parentid (.getParentIdHex tcd)
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
       :tdata    (zu/b64enc (.getTraceData tcd))
       :terms     (seq (.getTerms tcd))
       :mids     (seq (.getMethods tcd))}
      (into {}
        (for [[k v] (.getAttrs tcd) :let [f (str->field k) ]]
          {f (str (.replace v \tab \space) \tab (.replace k \tab \space))})))))

(def RE-ATTR #"([^\t]*)\t(.*)")

(defn doc->tcd [{:keys [traceid spanid parentid chnum tst duration klass method calls errors recs tdata ttype term mids] :as doc}]
  (let [[tid1 tid2] (zu/parse-hex-tid traceid)
        [spanid _] (zu/parse-hex-tid spanid)
        [pid _] (zu/parse-hex-tid parentid)
        rslt (TraceChunkData. tid1 tid2 spanid (or pid 0) chnum)]
    (when tst (.setTstamp rslt tst))
    (when duration (.setDuration rslt duration))
    (when class (.setKlass rslt klass))
    (when method (.setMethod rslt method))
    (when calls (.setCalls rslt calls))
    (when errors (.setErrors rslt errors))
    (when recs (.setRecs rslt recs))
    (when tdata (.setTraceData rslt (zu/b64dec tdata)))
    (when ttype (.setTtype rslt ttype))
    ; TODO (doseq [a attr :let [[_ v k] (re-matches RE-ATTR a)]] (.setAttr rslt k v))
    (doseq [t term] (.addTerm rslt t))
    (doseq [m mids] (.addMethod rslt (.intValue m)))
    rslt))

(def RE-TRC-CHUNK-ID #"TRC\.([0-9a-fA-F]{8,16})\.([0-9a-fA-F]{8})\.([0-9a-zA-Z]{8})\.([0-9a-zA-Z]+)")

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

(defn elastic-trace-store [tstore old-conf old-store]
  (let [state (or old-store (atom {:tsnum 1}))]
    (locking state
      (let [indexes (list-indexes tstore)
            tsnum (if (empty? indexes) 1 (apply max (map :tsnum indexes)))
            mapper (symbol-mapper tstore tsnum),
            store (chunk-store tstore tsnum),
            resolver (symbol-resolver tstore tsnum),
            collector (Collector. tsnum mapper store false)]
        (when (empty? indexes) (index-create tstore tsnum))
        (swap! state assoc :tsnum tsnum, :mapper mapper, :store store, :collector collector, :resolver resolver)))
    state))

(defn q->e [{:keys [errors-only spans-only traceid spanid order-by order-dir
                    min-tstamp max-tstamp min-duration limit offset
                    attr-matches text match-start match-end]}]
  {:sort
   (if order-by
     {order-by {:order (or order-dir :desc)}}
     {:tstamp {:order :desc}})
   :from (or offset 0)
   :size (or limit 100)
   :query
   {:bool
    {:must
     (filter
       some?
       [{:term {:doctype TYPE-CHUNK}}
        (when traceid {:term {:traceid traceid}})
        (when spanid {:term {:spanid spanid}})
        (when min-duration {:range {:duration {:gte min-duration}}})
        (when (or min-tstamp max-tstamp)
          {:range {:tstamp (into {}
                             (when min-tstamp {:gte min-tstamp})
                             (when max-tstamp {:lte max-tstamp}))}})
        ])}}})


(def RSLT-FIELDS [:traceid :spanid :parentid :ttype :tstamp :duration :calls :errors :recs
                  :klass :method])

(def RE-ATTRF #"attrs\.(.*)")
(def RE-ATTRV #"(.*)\t(.*)")

(defn doc->rest [{:keys [tstamp] :as doc} & {:keys [chunks?]}]
  (merge
    (assoc
      (select-keys doc RSLT-FIELDS)
      :tstamp (.toString (LocalDateTime/ofEpochSecond (/ tstamp 1000), 0, (ZoneOffset/ofHours 0)))
      :tst tstamp
      :attrs (into {}
               (for [[k v] doc :when (re-matches RE-ATTRF (name k))
                     :let [[_ s a] (re-matches RE-ATTRV v)]
                     :when (and (string? s) (string? a))]
                 {a s})))
    (when chunks? {:tdata (:tdata doc)})))


(defn trace-search [db query & {:keys [chunks?]}]
  (let [body (q->e query)
        _source (clojure.string/join "," (map name RSLT-FIELDS))
        rslt (elastic http/get db 1
                      :path ["/_search?_source=" _source ",attrs.*" (if chunks? ",tdata" "")]
                      :body body)]
    (for [doc (-> rslt :hits :hits) :let [doc (:_source (zu/keywordize doc))]]
      (doc->rest doc :chunks? chunks?))))

