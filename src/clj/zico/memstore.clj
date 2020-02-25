(ns zico.memstore
  (:require
    [zico.util :as zu]
    [zico.trace :as zt])
  (:import (com.jitlogic.zorka.common.collector MemoryChunkStore Collector TraceChunkData TraceChunkSearchQuery)))

(defmethod zt/attr-vals :memory [{:keys [trace-store]} attr]
  (seq (.attrVals trace-store attr)))


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


(defmethod zt/trace-search :memory [{:keys [^MemoryChunkStore trace-store]} query {:keys [raw?]}]
  (let [q (q->tcq query)] (map (if raw? identity zt/tcd->rest) (.search trace-store q))))


(defmethod zt/new-trace-store :memory [{:keys [trace-store trace-collector] :as app-state} old-state]
  (let [new-conf (-> app-state :conf :tstore)
        max-size (* 1024 1024 (:memstore-size-max new-conf 2048)),
        del-size (* 1024 1024 (:memstore-size-del new-conf 1024))
        trace-store (or trace-store (MemoryChunkStore. max-size del-size))
        trace-collector (or trace-collector (Collector. trace-store false))]
    (.setMaxSize trace-store max-size)
    (.setDelSize trace-store del-size)
    {:trace-store trace-store, :trace-collector trace-collector}))
