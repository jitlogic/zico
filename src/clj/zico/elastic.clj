(ns zico.elastic
  "Simple API to Elastic Search."
  (:require
    [zico.util :as zu]
    [clojure.string :refer [join]]
    [clojure.data.json :as json]
    [clj-http.client :as http]
    [clojure.set :as cs])
  (:import
    (java.util HashMap Set Map)
    (com.jitlogic.zorka.common.collector SymbolResolver SymbolMapper TraceChunkData TraceChunkStore Collector)
    (com.jitlogic.zorka.common.tracedata SymbolicMethod)
    (java.util.regex Pattern)))

(def TYPE-SYMBOL 1)
(def TYPE-METHOD 2)
(def TYPE-CHUNK 3)
(def TYPE-SEQ 4)

(def DOC-MAPPINGS
  {:doctype {:type :long}
   :tid {:type :text}
   :sid {:type :text}
   :pid {:type :text}
   :chnum {:type :long}
   :tstamp {:type :date}
   :duration {:type :long}
   :method {:type :text}
   :calls {:type :long}
   :errors {:type :long}
   :recs {:type :long}
   :tdata {:type :binary, :index false}
   :tstart {:type :date}
   :tstop {:type :date}
   :ttype {:type :text, :analyzer :keyword}
   :attr {:type :text}
   :term {:type :text}
   :mids {:type :long}

   ; symbol registries and sequence generators
   :mdesc {:type :keyword}
   :symbol {:type :keyword}
   :seq {:type :long}
   })

(defn parse-response [resp]
  (when (string? (:body resp))
    (json/read-str (:body resp))))

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

(defn- index-headers [db tsnum]
  {:accept "application/json", :content-type "application/json"})

(defn- elastic [http-method db tsnum & {:keys [path body]}]
  (-> (http-method
        (apply str (format "%s/%s_%06x" (:url db) (:name db) tsnum) path)
        (merge
          {:headers (index-headers db tsnum)}
          (when (map? body) {:body (json/write-str body)})
          (when (string? body) {:body body})))
      parse-response
      zu/keywordize))

(defn index-create [db tsnum]
  (elastic
    http/put db tsnum
    :body {:settings
                     {:number_of_shards   (:num-shards db 1)
                      :number_of_replicas (:num-replicas db 0)}
           :mappings {:properties DOC-MAPPINGS}}))

(defn index-delete [db tsnum]
  (elastic http/delete db tsnum))

(defn seq-next [db tsnum seq-name block-sz]
  (let [id0 (->
              (elastic
                http/post db tsnum
                :path ["/_update/SEQ." (name seq-name) "?_source=true"]
                :body {:script (format "ctx._source.seq += %d" block-sz)
                       :upsert {:seq 1, :type TYPE-SEQ}})
              :get :_source :seq)]
    (range id0 (+ id0 block-sz))))

(defn syms-add [db tsnum syms]
  (let [idx (format "%s_%06x" (:name db) tsnum)
        rslt (zipmap syms (seq-next db tsnum :SYMBOLS (count syms)))
        data (for [[s i] rslt]
               [{:index {:_index idx, :_id (str "SYM." i)}}
                {:doc-type TYPE-SYMBOL, :symbol s}])
        body (str (join "\n" (map json/write-str (apply concat data))) "\n")]
    (elastic
      http/post db tsnum
      :path ["/_bulk"]
      :body body)                                           ; TODO sprawdzic czy poprawnie sie dodaly
    rslt))

(defn syms-resolve [db tsnum sids]
  (let [docs (elastic http/get db tsnum :path ["/_mget"]
                      :body {:docs (for [s sids] {:_id (str "SYM." s)})})]
    (into {}
      (for [d (:docs docs)]
        {(Long/parseLong (.substring ^String (get d "_id") 4))
         (get-in d ["_source" "symbol"])}))))

(defn syms-search [db tsnum syms]
  (let [idx (format "%s_%06x" (:name db) tsnum)
        data (for [s syms]
               [{:index idx}
                {:query {:term {:symbol {:value s}}}}])
        body (str (join "\n" (map json/write-str (apply concat data))) "\n")
        rslt (elastic http/get db tsnum :path ["/_msearch"] :body body)]
    (into {}
      (for [r (:responses rslt) :let [h (first (get-in r ["hits" "hits"]))] :when h]
        {(get-in h ["_source" "symbol"]) (Long/parseLong (.substring ^String (get h "_id") 4))}))))

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
  (let [idx (format "%s_%06x" (:name db) tsnum),
        rslt (zipmap mdescs (seq-next db tsnum :METHODS (count mdescs)))
        data (for [[[c m s] i] rslt]
               [{:index {:_index idx, :_id (str "MID." i)}}
                {:doc-type TYPE-METHOD, :mdesc (str c "," m "," s)}])
        body (str (join "\n" (map json/write-str (apply concat data))) "\n")]
    (elastic
      http/post db tsnum :path ["/_bulk"], :body body)
    rslt))

(defn mids-resolve [db tsnum mids]
  (let [docs (elastic http/get db tsnum :path ["/_mget"]
                      :body {:docs (for [m mids] {:_id (str "MID." m)})})]
    (into {}
      (for [d (:docs docs) :let [mdesc (get-in d ["_source" "mdesc"])]]
        {(Long/parseLong (.substring ^String (get d "_id") 4))
         (vec (for [i (.split mdesc ",")] (Long/parseLong i)))}))))

(defn mids-search [db tsnum mdescs]
  (let [idx (format "%s_%06x" (:name db) tsnum),
        data (for [[c m s] mdescs]
               [{:index idx}
                {:query {:term {:mdesc {:value (str c "," m "," s)}}}}])
        body (str (join "\n" (map json/write-str (apply concat data))) "\n")
        rslt (elastic http/get db tsnum :path ["/_msearch"] :body body)]
    (into {}
      (for [r (:responses rslt) :let [h (first (get-in r ["hits" "hits"]))] :when h]
        {(vec (for [i (.split (get-in h ["_source" "mdesc"]) ",")] (Long/parseLong i)))
         (Long/parseLong (.substring ^String (get h "_id") 4))}))))

(defn mids-map [db tsnum m]
  "Given agent-side mid map (aid -> [c,m,s]), produce agent-collector mapping (aid -> rid)"
  (let [mdefs (set (vals m)),
        rsmap (mids-search db tsnum mdefs),
        amids (cs/difference mdefs (keys rsmap)),
        asmap (syms-add db tsnum amids)]
    (into {}
      (for [[aid mdef] m :let [rid (or (rsmap mdef) (asmap mdef))] :when rid]
        {aid rid}))))

(defn symbol-resolver [db tsnum]
  "Returns SymbolResolver with Elastic Search as backend."
  (reify
    SymbolResolver
    (^Map resolveSymbols [_ ^Set sids]
           (let [rslt (HashMap.)]
             (doseq [[sid sym] (syms-resolve db tsnum (seq sids))]
               (.put rslt (.intValue sid) sym))
             ^Map rslt))
    (^Map resolveMethods [_ ^Set mids]
      (let [rslt (HashMap.),
            mdss (mids-resolve db tsnum (seq mids))
            sids (into #{} (concat (vals mdss)))
            syms (syms-resolve db tsnum sids)]
        (doseq [[[c m s] i] mdss :let [cs (syms c), ms (syms m)] :when (and cs ms)]
          (.put rslt (.intValue i) (str cs "." ms "()")))
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
          (.put rslt (.intValue aid) (.intValue rid)))))
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
    {:type     TYPE-CHUNK
     :tid      (.getTraceIdHex tcd)
     :sid      (.getSpanIdHex tcd)
     :pid      (.getParentIdHex tcd)
     :chnum    (.getChunkNum tcd)
     :tstamp   (millis->date (.getTstamp tcd))
     :duration (- (.getTstop tcd) (.getTstart tcd))
     :method   (.getMethod tcd)
     :calls    (.getCalls tcd)
     :errors   (.getErrors tcd)
     :recs     (.getRecs tcd)
     :tdata    (zu/b64enc (.getTraceData tcd))
     :ttype    (.getAttr tcd "component")
     :attr     (when (.getAttrs tcd) (for [[k v] (.getAttrs tcd)] (str k "=" v)))
     :term     (seq (.getTerms tcd))
     :mids     (seq (.getMethods tcd))}))

(def RE-ATTR #"([^=])=(.*)")

(defn doc->tcd [{:keys [tid sid pid chnum tstamp duration method calls errors recs tdata ttype attr term mids]}]
  (let [[tid1 tid2] (zu/parse-hex-tid tid)
        [sid _] (zu/parse-hex-tid sid)
        [pid _] (zu/parse-hex-tid pid)
        rslt (TraceChunkData. tid1 tid2 sid (or pid 0) chnum)]
    (when tstamp (.setTstamp rslt tstamp))
    (when method (.setMethod rslt method))
    (when calls (.setCalls rslt calls))
    (when errors (.setErrors rslt errors))
    (when recs (.setRecs rslt recs))
    (when tdata (.setTraceData rslt (zu/b64dec tdata)))
    (doseq [a attr :let [[_ k v] (re-matches RE-ATTR a)]] (.setAttr rslt k v))
    (doseq [t term] (.addTerm rslt t))
    (doseq [m mids] (.addMethod rslt (.intValue m)))
    rslt))

(defn chunk-add [db tsnum {:keys [tid sid pid chnum] :as doc}]
  (let [id (str "TRC." tid "." sid "." (or pid "00000000") "." chnum)]
    (elastic http/post db tsnum :path ["/_doc/" id] :body doc)))

(defn chunk-store [db tsnum]
  "Returns trace chunk store with Elastic Search backend."
  (reify
    TraceChunkStore
    (add [_ tcd]
      (chunk-add db tsnum (tcd->doc tcd)))
    (addAll [_ tcds]
      (doseq [tcd tcds]
        (chunk-add db tsnum (tcd->doc tcd))))))

(defn elastic-trace-store [{:keys [tstore] :as new-conf} old-conf old-store]
  (let [state (or old-store (atom {:tsnum 1}))]
    (locking state
      (let [indexes (list-indexes tstore)
            tsnum (if (empty? indexes) 1 (apply max (map :tsnum indexes)))
            mapper (symbol-mapper tstore tsnum),
            store (chunk-store tstore tsnum)
            collector (Collector. tsnum mapper store false)]
        (swap! state assoc :tsnum tsnum, :mapper mapper, :store store, :collector collector)))
    state))

(defn req->query [{:keys [fetch-attrs errors-only spans-only
                                  min-tstamp max-tstamp min-duration
                                  attr-matches text match-start match-end]}]
  )
