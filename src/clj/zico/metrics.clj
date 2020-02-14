(ns zico.metrics
  (:require [zico.util :as zu])
  (:import
    (java.time Duration)
    (io.micrometer.core.instrument Clock Tag Timer)
    (io.micrometer.elastic ElasticConfig ElasticMeterRegistry)
    (io.micrometer.prometheus PrometheusConfig PrometheusMeterRegistry)
    (io.micrometer.core.instrument.binder.jvm ClassLoaderMetrics JvmMemoryMetrics JvmGcMetrics JvmThreadMetrics)
    (io.micrometer.core.instrument.binder.system FileDescriptorMetrics ProcessorMetrics UptimeMetrics)))

(defn elastic-config [conf]
  (reify
    ElasticConfig
    (get [_ k] nil)
    (prefix [_] (:prefix conf))
    (step [_] (Duration/ofSeconds (:step conf)))
    (numThreads [_] (:threads conf))
    (connectTimeout [_] (Duration/ofSeconds (:conn-timeout conf)))
    (readTimeout [_] (Duration/ofSeconds (:read-timeout conf)))
    (batchSize [_] (int (:batch-size conf)))
    (host [_] (:host conf))
    (index [_] (:index conf))
    (userName [_] (:username conf))
    (password [_] (:password conf))))

(defn prometheus-config [conf]
  (reify
    PrometheusConfig
    (get [_ k] nil)
    (prefix [_] (:prefix conf))
    (step [_] (Duration/ofSeconds (:step conf)))))

(defn new-registry [{:keys [type] :as conf}]
  (case type
    :elastic (ElasticMeterRegistry. (elastic-config conf) Clock/SYSTEM)
    :prometheus (PrometheusMeterRegistry. (prometheus-config conf))
    (throw (ex-info (str "Unknown metrics type: " type) {:type type}))))

(defn with-jvm-metrics [registry]
  (.bindTo (ClassLoaderMetrics.) registry)
  (.bindTo (JvmMemoryMetrics.) registry)
  (.bindTo (JvmGcMetrics.) registry)
  (.bindTo (JvmThreadMetrics.) registry)
  registry)

(defn with-os-metrics [registry]
  (.bindTo (FileDescriptorMetrics.) registry)
  (.bindTo (ProcessorMetrics.) registry)
  (.bindTo (UptimeMetrics.) registry)
  registry)

(defn make-tags [tags]
  (for [[k v] tags] (Tag/of (name k) (zu/to-str v))))

(defn make-counter [registry name & {:as tags}]
  (.counter registry name (make-tags tags)))

(defn make-timer [registry name & {:as tags}]
  (.timer registry name (make-tags tags)))

(defmacro with-timer [timer & body]
  `(let [sample# (Timer/start), ret# (do ~@body)]
     (when ~timer (.stop sample# ~timer))
     ret#))

(defn with-metrics-registry [app-state old-state]
  (let [registry (-> (new-registry (-> app-state :conf :metrics)) with-jvm-metrics with-os-metrics)]
    (assoc app-state
      :metrics {:registry registry
                :agent-agd (make-timer registry "zico.agent" :action :submit-agd)
                :agent-trc (make-timer registry "zico.agent" :action :submit-trc)
                :user-search (make-timer registry "zico.user" :action :search)
                :user-dtrace (make-timer registry "zico.user" :action :dtrace)
                :user-detail (make-timer registry "zico.user" :action :detail)
                :user-tstats (make-timer registry "zico.user" :action :tstats)})))

(defn prometheus-scrape [{{:keys [registry]} :metrics}]
  (if (instance? PrometheusMeterRegistry registry)
    {:status 200
     :body (.scrape registry)
     :headers {"Content-Type" "text/plain"}}
    {:status 404
     :body "Prometheus metrics not configured."
     :headers {"Content-Type" "text/plain"}}))

