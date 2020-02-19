(ns zico.test-util
  (:require
    [zico.main :as zsvr]
    [zico.util :as zu]
    [clojure.java.io :as io]
    [zico.elastic :as ze]
    [clj-http.client :as http]
    [zico.metrics :as zmet])
  (:import
    (java.io File ByteArrayInputStream)
    (com.jitlogic.zorka.common.cbor TraceDataWriter CborDataWriter)
    (io.micrometer.core.instrument.simple SimpleMeterRegistry)))

(def cur-time-val (atom 100))

(defn time-travel [t]
  (reset! cur-time-val t))

(def ^:dynamic *root-path* nil)
(def ^:dynamic zico nil)

(def ^:dynamic trace-store nil)

(defn cur-time-mock
  ([] @cur-time-val)
  ([offs] (+ offs @cur-time-val)))

(defn rm-rf [^File f]
  (when (.isDirectory f)
    (do
      (doseq [^String n (.list f)]
        (rm-rf (File. f n)))))
  (.delete f))

(def REGISTRY (SimpleMeterRegistry.))

(def ^:dynamic *DB*
  {:url "http://127.0.0.1:9200"
   :name "zicotest"
   :seq-block-size 16
   :instance "00"})

(def ^:dynamic *APP-STATE*
  {:conf    {:tstore *DB*}
   :metrics {:registry    REGISTRY
             :symbols-seq (zmet/make-timer REGISTRY "zico.symbols" :action :seq-next)
             :symbols-get (zmet/make-timer REGISTRY "zico.symbols" :action :get-symbols)
             :symbols-mid (zmet/make-timer REGISTRY "zico.symbols" :action :get-methods)}})

(defn elastic-ping []
  (try
    (let [rslt (http/get (:url *DB*))]
      (= 200 (:status rslt)))
    (catch Exception _ false)))

(defn elastic-integ-fixture [f]
  (if (elastic-ping)
    (do
      (doseq [idx (ze/list-data-indexes *DB*)]
        (ze/index-delete *DB* (:tsnum idx)))
      (ze/index-create *DB* 1)
      (f))
    (do
      (println "WARNING: test not executed as ElasticSearch instance was not present.")
      (println "Please run Elastic OSS instance in its default configuration on " (:url *DB*)))))

(defn- traverse-trace [^TraceDataWriter tdw vs]
  (cond
    (vector? (first vs))
    (doseq [v vs] (traverse-trace tdw v))
    (keyword? (first vs))
    (case (first vs)
      :sref
      (let [[_ id v] vs]
        (.stringRef tdw id v))
      :mref
      (let [[_ id c m s] vs]
        (.methodRef tdw id c m s))
      :start
      (let [[_ pos tstart mid & vss] vs]
        (.traceStart tdw pos tstart mid)
        (doseq [v vss] (traverse-trace tdw v)))
      :end
      (let [[_ pos tstop calls flags] vs]
        (.traceEnd tdw pos tstop calls flags))
      :begin
      (let [[_ tstamp ttid spanid parentid] vs]
        (.traceBegin tdw tstamp ttid spanid parentid))
      :attr
      (let [[_ id v] vs]
        (.traceAttr tdw id v))
      )))

(defn trace-data [trc]
  (let [cbw (CborDataWriter. 512 512)
        tdw (TraceDataWriter. cbw)]
    (traverse-trace tdw trc)
    (.toByteArray cbw)))

(defn zorka-integ-fixture [f]
  (let [conf (zu/recursive-merge
               (aero.core/read-config (io/resource "zico/zico.edn"))
               {:home-dir ".", :tstore *DB*
                :log {:console-level :info}})
        app-state (zsvr/new-app-state {} conf)]
    (with-redefs [zu/cur-time cur-time-mock]
      (reset! cur-time-val 100)
      (binding [zsvr/zorka-app-state app-state
                zico  (:main-handler app-state)
                trace-store (:tstore app-state)]
        (f)))))
