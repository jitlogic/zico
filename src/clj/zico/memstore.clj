(ns zico.memstore
  (:require [zico.util :as zu])
  (:import (com.jitlogic.zorka.common.collector MemoryChunkStore Collector TraceChunkData TraceDataExtractor TraceStatsExtractor TraceChunkSearchQuery)
           (com.jitlogic.zorka.common.tracedata SymbolRegistry TraceMarker)
           (java.time LocalDateTime OffsetDateTime)
           (java.util ArrayList Collection)))

(defn attr-vals [{:keys [tstore-state]} attr]
  (let [{:keys [^MemoryChunkStore store]} @tstore-state]
    (seq (.attrVals store attr))))

(defn- tcd-matches [{:keys [traceid spanid errors-only spans-only min-tstamp max-tstamp min-duration attr-matches text]} ^TraceChunkData tcd]
  (let [min-tstamp (zu/iso-time->millis min-tstamp), max-tstamp (zu/iso-time->millis max-tstamp)]
    (and
      (or (nil? traceid) (= traceid (.getTraceIdHex tcd)))
      (or (nil? spanid) (= spanid (.getSpanIdHex tcd)))
      (or (not errors-only) (.hasFlag tcd TraceMarker/ERROR_MARK))
      (or (nil? text) (not (empty? (for [[_ t] (.getAttrs tcd) :when (some? t)
                                         :when (.contains (.toUpperCase t) (.toUpperCase text))] t))))
      (or (nil? attr-matches) (every? true? (for [[k v] attr-matches :let [s (.getAttr tcd k)]]
                                              (and (some? s) (.contains s v)))))
      (or (nil? min-duration) (> (.getDuration tcd) min-duration))
      (or (nil? min-tstamp) (>= (.getTstamp tcd) min-tstamp))
      (or (nil? max-tstamp) (<= (.getTstamp tcd) max-tstamp))
      (or spans-only (= 0 (.getParentId tcd)))
      ; TODO spans-only
      )))

(defn- tcd->rest [^TraceChunkData tcd & {:keys [chunks?]}]
  (when (some? tcd)
    (merge
      {:traceid  (.getTraceIdHex tcd)
       :spanid   (.getSpanIdHex tcd)
       :parentid (.getParentIdHex tcd)
       :ttype    (.getTtype tcd)
       :tst      (.getTstamp tcd)
       :tstamp   (zu/millis->iso-time (.getTstamp tcd))
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

(defn q->tcq [{:keys [traceid spanid errors-only spans-only min-tstamp max-tstamp min-duration attr-matches text
                      offset limit]}]
  (let [q (TraceChunkSearchQuery.)]
    (when traceid (.withTraceId q traceid))
    (when spanid (.withSpanId q spanid))
    (when errors-only (.setErrorsOnly q errors-only))
    (when spans-only (.setSpansOnly q spans-only))
    (when min-tstamp (.setMinTstamp q (zu/iso-time->millis min-tstamp)))
    (when max-tstamp (.setMaxTstamp q (zu/iso-time->millis max-tstamp)))
    (when min-duration (.setMinDuration q min-duration))
    (when text (.setText q text))
    (when offset (.setOffset q offset))
    (when limit (.setLimit q limit))
    (doseq [[k v] attr-matches] (.withAttr q k v))
    q))

(defn trace-search [{:keys [tstore-state]} query & {:keys [raw?]}]
  (let [q (q->tcq query), {:keys [^MemoryChunkStore store]} @tstore-state]
    (map (if raw? identity tcd->rest) (.search store q))))

(defn trace-detail [{:keys [tstore-state] :as app-state} traceid spanid]
  (let [{:keys [search resolver]} @tstore-state,
        chunks (search app-state {:traceid traceid, :spanid spanid, :spans-only true} :chunks? true, :raw? true)
        tex (TraceDataExtractor. resolver),
        rslt (.extract tex (ArrayList. ^Collection chunks))]
    rslt))

(defn trace-stats [{:keys [tstore-state] :as app-state} traceid spanid]
  (let [{:keys [search resolver]} @tstore-state,
        chunks (search app-state {:traceid traceid :spanid spanid} :chunks? true, :raw? true),
        tex (TraceStatsExtractor. resolver),
        rslt (.extract tex (ArrayList. ^Collection chunks))]
    rslt))

(defn memory-trace-store [app-state old-state]
  (let [new-conf (-> app-state :conf :tstore)
        max-size (* 1024 1024 (:memstore-size-max new-conf 2048)),
        del-size (* 1024 1024 (:memstore-size-del new-conf 1024))
        tstore-state (:tstore-state old-state)]
    (if tstore-state
      (let [{:keys [store] :as tstore-state} @tstore-state]
        (.setMaxSize store max-size)
        (.setDelSize store del-size)
        tstore-state)
      (let [store (MemoryChunkStore. max-size del-size), sreg (SymbolRegistry.),
            collector (Collector. sreg store false)]
        {:store store, :collector collector, :registry sreg, :resolver sreg,
         :search trace-search, :detail trace-detail, :stats trace-stats, :attr-vals attr-vals}))))
