(ns zico.memstore
  (:import (com.jitlogic.zorka.common.collector MemoryChunkStore Collector TraceChunkData TraceDataExtractor TraceStatsExtractor)
           (com.jitlogic.zorka.common.util ZorkaUtil)
           (com.jitlogic.zorka.common.tracedata SymbolRegistry TraceMarker)
           (java.time LocalDateTime OffsetDateTime)
           (java.util ArrayList Collection)))

(defn attr-vals [{{:keys [^MemoryChunkStore store]} :tstore} attr]
  (seq (.attrVals store attr)))

(defn- tcd-matches [{:keys [traceid spanid errors-only spans-only min-tstamp max-tstamp min-duration attr-matches text]} ^TraceChunkData tcd]
  (and
    (or (nil? traceid) (= traceid (.getTraceIdHex tcd)))
    (or (nil? spanid) (= spanid (.getSpanIdHex tcd)))
    (or (not errors-only) (.hasFlag tcd TraceMarker/ERROR_MARK))
    (or (nil? text) (some? (for [t (.getTerms tcd) :when (.contains t text)] t)))
    (or (nil? attr-matches) (every? true? (for [[k v] attr-matches :let [s (.getAttr tcd k)]]
                                            (and (some? s) (.contains s v)))))
    (or (nil? min-duration) (> (.getDuration tcd) min-duration))
    ; TODO spans-only, min-tstamp, max-tstamp
    ))

(defn- tcd->rest [^TraceChunkData tcd & {:keys [chunks?]}]
  (when (some? tcd)
    (merge
      {:traceid  (.getTraceIdHex tcd)
       :spanid   (.getSpanIdHex tcd)
       :parentid (.getParentIdHex tcd)
       :ttype    (.getTtype tcd)
       :tst      (.getTstamp tcd)
       :tstamp   (.toString (LocalDateTime/ofEpochSecond (/ (.getTstamp tcd) 1000), 0, (.getOffset (OffsetDateTime/now))))
       :duration (.getDuration tcd)
       :calls    (.getCalls tcd)
       :errors   (.getErrors tcd)
       :recs     (.getRecs tcd)
       :klass    (.getKlass tcd)
       :method   (.getMethod tcd)
       :tsnum 1}
      (when chunks? {:tdata (.getTraceData tcd)})
      (when (.getAttrs tcd) {:attrs (into {} (for [[k v] (.getAttrs tcd)] {k v}))})
      )))

(def TCD-SORT-FNS
  {:tst #(.getTstamp %), :duration #(.getDuration %),
   :calls #(.getCalls %), :recs #(.getRecs %), :errors #(.getErrors %)})

(defn trace-search [{{:keys [^MemoryChunkStore store]} :tstore} query & {:keys [raw?]}]
  (let [odir (if (= :asc (:order-dir query)) identity reverse)
        mfn (if raw? identity tcd->rest)]
    (take
      (:limit query 100)
      (drop
        (:offset query 0)
        (for [tcd (odir (sort-by (TCD-SORT-FNS (:order-by query :tst)) (.getChunks store)))
              :when (and tcd (tcd-matches query tcd))]
          (mfn tcd))))))

(defn trace-detail [{{:keys [search resolver]} :tstore :as app-state} traceid spanid]
  (let [chunks (search app-state {:traceid traceid, :spanid spanid, :spans-only true} :chunks? true, :raw? true)
        tex (TraceDataExtractor. resolver)
        rslt (.extract tex (ArrayList. ^Collection chunks))]
    rslt))

(defn trace-stats [{{:keys [search resolver]} :tstore :as app-state} traceid spanid]
  (let [chunks (search app-state {:traceid traceid :spanid spanid} :chunks? true, :raw? true)
        tex (TraceStatsExtractor. resolver)
        rslt (.extract tex (ArrayList. ^Collection chunks))]
    rslt))

(defn memory-trace-store [app-state old-state]
  (let [new-conf (-> app-state :conf :tstore)
        store (MemoryChunkStore. (* 1048576 (:max-size new-conf 4096))),
        sreg (or (-> old-state :tstore :resolver) (SymbolRegistry.)),
        state (or (:tstore old-state) {:store store, :collector (Collector. 1 sreg store false), :registry sreg})]
    (assoc state
      :search trace-search, :detail trace-detail, :stats trace-stats, :attr-vals attr-vals, :resolver sreg)))