(ns zico.server
  (:require [zico.web :as zweb]
            [zico.util :as zutl]
            [zico.trace :as ztrc]
            [org.httpkit.server :as hsv]
            [clojure.java.io :as io]
            [compojure.core :refer [GET routes]]
            [taoensso.timbre.appenders.3rd-party.rotor :refer [rotor-appender]]
            [taoensso.timbre :as log]
            [ns-tracker.core :refer [ns-tracker]]
            [ring.middleware.session.memory]
            [zico.objstore :as zobj])
  (:gen-class)
  (:import (org.slf4j.impl ZorkaLoggerFactory ConsoleTrapper ZorkaTrapper ZorkaLogLevel)))


(def ^:private SRC-DIRS ["src" "env/dev"])

(def ^:private NS-TRACKER (ns-tracker SRC-DIRS))

(defn check-reload [reload-fn]
  (when (.equalsIgnoreCase "true" (System/getProperty "zico.dev.mode"))
    (let [ns-syms (NS-TRACKER)]
      (when-not (empty? ns-syms)
        (log/info "Code changes detected. Reloading required namespaces:" (str (into [] ns-syms)))
        (doseq [sym ns-syms :when (not (contains? #{'zico.macros} sym))]
          (println "Reloading: " sym)
          (require sym :reload))
        (log/info "Reloading configuration.")
        (reload-fn)))))


(def DEFAULT-HTTP-CONF
  {:ip "0.0.0.0"
   :port 6841
   :thread 16
   :queue-size 65536
   :worker-name-prefix "zico-http-"
   :max-body 8388608
   :max-line 4096})

(def DEFAULT-CONF
  (read-string (slurp (io/resource "zico/zico.conf"))))


(defonce ^:dynamic zorka-app-state {})
(defonce ^:dynamic stop-f (atom nil))
(defonce ^:dynamic conf-autoreload-f (atom nil))


(defn  new-app-state [old-state conf]
  (let [zico-db (zobj/jdbc-reconnect (:zico-db old-state) (:zico-db (:conf old-state)) (:zico-db conf)),
        obj-store (zobj/jdbc-caching-store zico-db)]
    (zobj/jdbc-migrate zico-db)
    (zobj/load-initial-data zico-db (:zico-db-init conf) (:home-dir conf))
    (zobj/refresh obj-store)
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
    (->
      conf
      (assoc :home-dir home-dir)
      (update-in [:backup-conf :path] updf)
      (update-in [:zico-db :subname] updf)
      (update-in [:trace-store :path] updf)
      (update-in [:log-conf :main :path] updf))))

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

(defn reload
  ([] (reload (System/getProperty "zico.home" (System/getProperty "user.dir"))))
  ([home-dir]
   (let [conf (load-conf (zutl/ensure-dir home-dir)), logs (-> conf :log-conf :main)]
     (zutl/ensure-dir (-> conf :backup-conf :path))
     (zutl/ensure-dir (-> conf :trace-store :path))
     (zutl/ensure-dir (-> conf :log-conf :main :path))
     ; TODO konfiguracja logów przed inicjacją serwera - tak aby logi slf4j trafiły od razu we właściwe miejsce
     (taoensso.timbre/merge-config!
       {:appenders {:rotor   (rotor-appender (assoc logs :path (str (:path logs) "/zico.log")))
                    :println {:enabled? false}}})
     (.swapTrapper (ZorkaLoggerFactory/getInstance) (timbre-trapper))
     (taoensso.timbre/set-level! (-> conf :log-conf :level))
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
  (when-let [cf @conf-autoreload-f]
    (future-cancel cf)
    (reset! conf-autoreload-f nil)))


(defn start-server []
  (stop-server)
  (reload)
  (let [{:keys [http-conf]} (:conf zorka-app-state)]
    (reset!
      conf-autoreload-f
      (zutl/conf-reload-task
        #'reload
        (System/getProperty "zico.home")
        "zico.conf"))
    (reset!
      stop-f
      (hsv/run-server
        #'zorka-main-handler
        (merge DEFAULT-HTTP-CONF http-conf)))
    (println "ZICO is running and listening on port" (:port http-conf)))
  )


(defn -main [& args]
  (start-server))

