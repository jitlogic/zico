(ns zico.main
  (:require
    [clojure.java.io :as io]
    [ring.adapter.jetty :refer [run-jetty]]
    [ns-tracker.core :refer [ns-tracker]]
    [zico.util :as zu]
    [zico.schema.server]
    [zico.trace :as ztrc]
    [zico.web :as zweb]
    [zico.metrics :as zmet]
    [clojure.tools.logging :as log]
    [zico.elastic :as ze])
  (:gen-class)
  (:import (ch.qos.logback.classic Level)
           (org.slf4j LoggerFactory Logger)
           (ch.qos.logback.classic.encoder PatternLayoutEncoder)
           (ch.qos.logback.core ConsoleAppender)
           (ch.qos.logback.core.rolling RollingFileAppender TimeBasedRollingPolicy)
           (com.jitlogic.zorka.common.collector Collector)
           (java.util.regex Pattern)))

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
(defonce ^:dynamic index-rotation-f (atom nil))
(defonce ^:dynamic session-cleaner-f (atom nil))

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
    (zmet/with-metrics-registry old-state)
    (ztrc/with-tracer-components old-state)
    zweb/with-zorka-web-handler))


(defn reload
  ([] (reload (System/getProperty "zico.home" (System/getProperty "user.dir"))))
  ([home-dir]
   (println "Zico home directory:" home-dir)
   (let [{:keys [attr-transforms] :as conf} (zu/read-config
                zico.schema.server/ZicoConf
                (io/resource "zico/zico.edn")
                (zu/to-path (zu/ensure-dir home-dir) "zico.edn"))
         attr-transforms (for [a attr-transforms] (assoc a :match (Pattern/compile (:match a))))]
     (configure-logger (-> conf :log))
     (zu/ensure-dir (-> conf :log :path))
     (alter-var-root #'zorka-app-state (constantly (new-app-state zorka-app-state conf)))
     (alter-var-root #'zico.elastic/ATTR-KEY-TRANSFORMS (constantly attr-transforms)))))


(defn zorka-main-handler [req]
  (check-reload #'reload)
  (if-let [main-handler (:main-handler zorka-app-state)]
    (main-handler req)
    {:status 500, :body "Application not initialized."}))

(defn index-rotation-task []
  (future
    (log/info "Started index rotation/removal task.")
    (loop []
      (let [conf (-> zorka-app-state :conf :tstore)]
        (zu/sleep 30000)
        (try
          (when (= :elastic (:type conf))
            (ze/check-rotate zorka-app-state))
          (catch Throwable e
            (log/error e "Error running index rotation check")))
        (recur)))))

(defn session-cleaner-task []
  (future
    (log/info "Started session cleaner task.")
    (loop []
      (zu/sleep 30000)
      (when-let [tstore-state (:tstore-state zorka-app-state)]
        (let [t (* 1000 (or (-> zorka-app-state :conf :tstore :session-timeout) 300))
              n (.cleanup (:collector @tstore-state) t)]
          (log/debug "Session cleanup cycle:" n "sessions removed.")))
      (recur))))

(defn stop-server []
  (when-let [f @stop-f]
    (f :timeout 1000)
    (reset! stop-f nil))
  (when-let [j @jetty-server]
    (.stop j)
    (reset! jetty-server nil))
  (when-let [f @index-rotation-f]
    (future-cancel f)
    (reset! index-rotation-f nil))
  (when-let [f @session-cleaner-f]
    (future-cancel f)
    (reset! session-cleaner-f nil))
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
    (reset! index-rotation-f (index-rotation-task))
    (reset! session-cleaner-f (session-cleaner-task))
    ; Set up Jetty container
    (System/setProperty "org.eclipse.jetty.server.Request.maxFormContentSize" (str (:max-form-size http)))
    (run-jetty zorka-main-handler http)
    (println "ZICO is up and running on: " (str (:ip http) ":" (:port http)))))

(defn gen-passwd []
  (let [p1 (String. (.readPassword (System/console) "Enter password: " (object-array [])))
        p2 (String. (.readPassword (System/console) "Repeat password: " (object-array [])))]
    (cond
      (empty? p1) (println "ERROR: Empty password.")
      (not= p1 p2) (println "ERROR: passwords don't match.")
      :else (println (zu/ssha512 p1)))))

(defn -main [& args]
  (println "args=" args)
  (cond
    (empty? args) (start-server)
    (= (first args) "passwd") (gen-passwd)
    :else (start-server)))

