(ns zico.main
  (:require
    [clojure.java.io :as io]
    [ring.adapter.jetty :refer [run-jetty]]
    [ns-tracker.core :refer [ns-tracker]]
    [zico.util :as zu]
    [zico.schema.server]
    [zico.trace :as ztrc]
    [zico.web :as zweb]
    [clojure.tools.logging :as log])
  (:gen-class)
  (:import (ch.qos.logback.classic Level)
           (org.slf4j LoggerFactory Logger)
           (ch.qos.logback.classic.encoder PatternLayoutEncoder)
           (ch.qos.logback.core ConsoleAppender)
           (ch.qos.logback.core.rolling RollingFileAppender TimeBasedRollingPolicy)))

(def ^:private SRC-DIRS ["src" "env/dev"])

(def ^:private NS-TRACKER (ns-tracker SRC-DIRS))

(def DEV-MODE (.equalsIgnoreCase "true" (System/getProperty "zico.dev.mode")))

(defn check-reload [reload-fn]
  (when DEV-MODE
    (let [ns-syms (NS-TRACKER)]
      (when-not (empty? ns-syms)
        (log/info "Code changes detected. Reloading required namespaces:" (str (into [] ns-syms)))
        (doseq [sym ns-syms :when (not (contains? #{'zico.macros} sym))]
          (println "Reloading: " sym)
          (require sym :reload))
        (log/info "Reloading configuration.")
        (reload-fn)))))

(defonce ^:dynamic zorka-app-state {})
(defonce ^:dynamic stop-f (atom nil))
(defonce ^:dynamic jetty-server (atom nil))
(defonce ^:dynamic conf-autoreload-f (atom nil))

(defonce LOG-STATE (atom {}))
(def LOG-LEVELS {:trace Level/TRACE, :debug Level/DEBUG, :info Level/INFO, :warn Level/WARN, :error Level/ERROR})

(defn configure-logger [{:keys [path max-history current-fname history-fname
                                console-pattern file-pattern  log-levels]}]
  (zu/ensure-dir path)
  (let [ctx (LoggerFactory/getILoggerFactory)
        c-encoder (or (:c-encoder @LOG-STATE) (PatternLayoutEncoder.))
        c-appender (or (:c-appender @LOG-STATE) (ConsoleAppender.))
        f-encoder (or (:f-encoder @LOG-STATE) (PatternLayoutEncoder.))
        f-appender (or (:f-appender @LOG-STATE) (RollingFileAppender.))
        f-policy (or (:f-policy @LOG-STATE) (TimeBasedRollingPolicy.))
        logger ^Logger (.getLogger ctx "ROOT")
        log-file (zu/to-path path current-fname)]
    (doto c-encoder (.setContext ctx) (.setPattern console-pattern) (.start))
    (doto c-appender (.setContext ctx) (.setName "console") (.setEncoder c-encoder) (.start))
    (doto f-encoder (.setContext ctx) (.setPattern file-pattern) (.start))
    (doto f-appender (.setContext ctx) (.setName "file") (.setEncoder f-encoder) (.setAppend true)
                     (.setFile log-file))
    (doto f-policy (.setContext ctx) (.setParent f-appender) (.setMaxHistory max-history)
                   (.setFileNamePattern (zu/to-path path history-fname)) (.start))
    (doto f-appender (.setRollingPolicy f-policy) (.start))
    (doto logger (.setAdditive true)
                 (.detachAppender "console") (.addAppender c-appender)
                 (.detachAppender "file") (.addAppender f-appender))
    (doseq [[k v] log-levels :let [l ^Logger (.getLogger ctx (name k))]] (.setLevel l (LOG-LEVELS v)))))


(defn  new-app-state [old-state conf]
  (->
    {:conf conf}
    (ztrc/with-tracer-components old-state)
    zweb/with-zorka-web-handler))


(defn reload
  ([] (reload (System/getProperty "zico.home" (System/getProperty "user.dir"))))
  ([home-dir]
   (println "Zico home directory:" home-dir)
   (let [conf (zu/read-config
                zico.schema.server/ZicoConf
                (io/resource "zico/zico.edn")
                (zu/to-path (zu/ensure-dir home-dir) "zico.edn"))]
     (configure-logger (-> conf :log))
     (zu/ensure-dir (-> conf :log :path))
     (alter-var-root #'zorka-app-state (constantly (new-app-state zorka-app-state conf))))))


(defn zorka-main-handler [req]
  (check-reload #'reload)
  (if-let [main-handler (:main-handler zorka-app-state)]
    (main-handler req)
    {:status 500, :body "Application not initialized."}))


(defn stop-server []
  (when-let [f @stop-f]
    (f :timeout 1000)
    (reset! stop-f nil))
  (when-let [j @jetty-server]
    (.stop j)
    (reset! jetty-server nil))
  (when-let [cf @conf-autoreload-f]
    (future-cancel cf)
    (reset! conf-autoreload-f nil)))

(defn start-server []
  (stop-server)
  (reload)
  (let [{:keys [http]} (:conf zorka-app-state)]
    (reset!
      conf-autoreload-f
      (zu/conf-reload-task #'reload (System/getProperty "zico.home") "zico.edn"))
    ; Set up Jetty container
    (System/setProperty "org.eclipse.jetty.server.Request.maxFormContentSize" (str (:max-form-size http)))
    (run-jetty zorka-main-handler http)
    (println "ZICO is up and running on: " (str (:ip http) ":" (:port http)))))


(defn -main [& args]
  (start-server))

