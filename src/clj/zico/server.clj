(ns zico.server
  (:require [zico.web :as zweb]
            [zico.util :as zutl]
            [zico.trace :as ztrc]
            [clojure.java.io :as io]
            [compojure.core :refer [GET routes]]
            [taoensso.timbre.appenders.3rd-party.rotor :refer [rotor-appender]]
            [taoensso.timbre :as log]
            [ns-tracker.core :refer [ns-tracker]]
            [ring.middleware.session.memory]
            [zico.objstore :as zobj]
            [clojure.set :as cs]
            [clojure.spec.alpha :as s]
            [zico.cfg])
  (:gen-class)
  (:import (org.slf4j.impl ZorkaLoggerFactory ZorkaTrapper ZorkaLogLevel)
           (com.jitlogic.netkit.integ.ring RingServerBuilder)
           (com.jitlogic.netkit.log LoggerFactory LoggerOutput)))


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

(def DEFAULT-CONF
  (read-string (slurp (io/resource "zico/zico.conf"))))


(defonce ^:dynamic zorka-app-state {})
(defonce ^:dynamic stop-f (atom nil))
(defonce ^:dynamic jetty-server (atom nil))
(defonce ^:dynamic conf-autoreload-f (atom nil))


(defn  new-app-state [old-state conf]
  (let [zico-db (zobj/jdbc-reconnect (:zico-db old-state) (:zico-db (:conf old-state)) (:zico-db conf)),
        obj-store (zobj/jdbc-store zico-db)]
    (zobj/jdbc-migrate zico-db)
    (zobj/load-initial-data obj-store (:zico-db conf) (:home-dir conf))
    (->
      {:conf conf, :zico-db zico-db, :obj-store obj-store,
       :session-store (or (:session-store old-state) (ring.middleware.session.memory/memory-store))}
      (ztrc/with-tracer-components old-state)
      zweb/with-zorka-web-handler)))

(defn load-conf [home-dir]
  (let [path (zutl/to-path home-dir "zico.conf")
        conf (if (zutl/is-file? path)
               (zutl/recursive-merge DEFAULT-CONF (read-string (slurp path)))
               DEFAULT-CONF)
        updf #(if (string? %) (.replace % "${zico.home}" home-dir))]
    (when-not (s/valid? :zico.cfg/config conf)
      (println "ERROR: invalid application configuration.")
      (s/explain :zico.cfg/config conf)
      (log/error "Invalid configuration" (str (s/explain-data :zico.cfg/config conf)))
      (throw (RuntimeException. "Invalid application configuration.")))
    (->
      conf
      (assoc :home-dir home-dir)
      (update-in [:backup :path] updf)
      (update-in [:zico-db :subname] updf)
      (update-in [:tstore :path] updf)
      (update-in [:log :main :path] updf))))

(def TRAPPER-LEVELS
  {ZorkaLogLevel/TRACE :trace,
   ZorkaLogLevel/DEBUG :debug,
   ZorkaLogLevel/INFO  :info,
   ZorkaLogLevel/WARN  :warn,
   ZorkaLogLevel/ERROR :error,
   ZorkaLogLevel/FATAL :fatal})

(defn timbre-trapper []
  (reify
    ZorkaTrapper
    (trap [_ level tag msg e args]
      (cond
        (nil? e) (log/log (TRAPPER-LEVELS level :info) tag msg (seq args))
        :else (log/log (TRAPPER-LEVELS level :info) e tag msg (seq args))))))

(def NETKIT_LEVELS
  {5 :trace, 4 :debug, 3 :info, 2 :warn, 1 :error, 0 :fatal})

(defn netkit-logger []
  (reify
    LoggerOutput
    (log [_ level tag msg e]
      (cond
        (nil? e) (log/log (NETKIT_LEVELS level :info) tag msg nil)
        :else (log/log (NETKIT_LEVELS level :info) e tag msg nil)))))

(defn reload
  ([] (reload (System/getProperty "zico.home" (System/getProperty "user.dir"))))
  ([home-dir]
   (let [conf (load-conf (zutl/ensure-dir home-dir)), logs (-> conf :log :main)]
     (zutl/ensure-dir (-> conf :backup :path))
     (zutl/ensure-dir (-> conf :tstore :path))
     (zutl/ensure-dir (-> conf :log :main :path))
     ; TODO konfiguracja logów przed inicjacją serwera - tak aby logi slf4j trafiły od razu we właściwe miejsce
     (taoensso.timbre/merge-config!
       {:appenders {:rotor   (rotor-appender (assoc logs :path (str (:path logs) "/zico.log")))
                    :println {:enabled? false}}})
     (.swapTrapper (ZorkaLoggerFactory/getInstance) (timbre-trapper))
     (LoggerFactory/setOutput (netkit-logger))
     (LoggerFactory/setLevel ((cs/map-invert NETKIT_LEVELS) (:level logs) 3))
     (taoensso.timbre/set-level! (-> conf :log :level))
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
      (zutl/conf-reload-task
        #'reload
        (System/getProperty "zico.home")
        "zico.conf"))
    ;(LoggerFactory/setDefaultLevel 5)
    (doto
      (RingServerBuilder/server
        (merge http {:handler zorka-main-handler}))
      (.start))
    (println "ZICO is up and running on: " (str (:ip http) ":" (:port http)))))


(defn -main [& args]
  (start-server))

