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
  (:gen-class))


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


(defn new-app-state [old-state conf]
  (let [zico-db (zobj/jdbc-reconnect (:zico-db old-state) (:zico-db (:conf old-state)) (:zico-db conf)),
        obj-store (zobj/jdbc-caching-store zico-db)]
    (zobj/jdbc-migrate zico-db)
    (zobj/refresh obj-store)
    (->
      {:conf conf, :zico-db zico-db, :obj-store obj-store,
       :session-store (or (:session-store old-state) (ring.middleware.session.memory/memory-store))}
      (ztrc/with-tracer-components old-state)
      zweb/with-zorka-web-handler)))


(defn reload
  ([] (reload (System/getProperty "zico.home")))
  ([home-dir]
   (let [conf (read-string (slurp (zutl/to-path home-dir "zico.conf")))
         conf  (zutl/recursive-merge DEFAULT-CONF conf)
         conf (assoc conf :home-dir home-dir)]

     ; TODO konfiguracja logów przed inicjacją serwera - tak aby logi slf4j trafiły od razu we właściwe miejsce
     (taoensso.timbre/merge-config!
       {:appenders {:rotor   (rotor-appender (-> conf :log-conf :main))
                    :println {:enabled? false}}})
     (taoensso.timbre/set-level! (-> conf :log-conf :level))
     (alter-var-root #'zorka-app-state (constantly (new-app-state zorka-app-state conf))))))


(defn zorka-main-handler [req]
  (check-reload #'reload)
  (if-let [web-handler (:web-handler zorka-app-state)]
    (web-handler req)
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
        (merge DEFAULT-HTTP-CONF http-conf))))
  (println "Server started."))


(defn -main [& args]
  (start-server))

