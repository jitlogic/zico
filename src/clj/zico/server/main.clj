(ns zico.server.main
  (:require
    [clojure.java.io :as io]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.middleware.session.memory]
    [ns-tracker.core :refer [ns-tracker]]
    [taoensso.timbre.appenders.3rd-party.rotor :refer [rotor-appender]]
    [taoensso.timbre :as log]
    [zico.backend.objstore :as zobj]
    [zico.backend.util :as zbu]
    [zico.server.schema :as zcfg]
    [zico.server.trace :as ztrc]
    [zico.server.web :as zweb])
  (:gen-class))


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

(def INIT-CLASSES [:app :env :hostreg :ttype :user])

(defn  new-app-state [old-state conf]
  (let [zico-db (zobj/jdbc-reconnect (:zico-db old-state) (:zico-db (:conf old-state)) (:zico-db conf)),
        obj-store (zobj/jdbc-store zico-db)]
    (zobj/jdbc-migrate zico-db)
    (zobj/load-initial-data obj-store INIT-CLASSES (:zico-db conf) (:home-dir conf))
    (->
      {:conf conf, :zico-db zico-db, :obj-store obj-store,
       :session-store (or (:session-store old-state) (ring.middleware.session.memory/memory-store))}
      (ztrc/with-tracer-components old-state)
      zweb/with-zorka-web-handler)))


(defn reload
  ([] (reload (System/getProperty "zico.home" (System/getProperty "user.dir"))))
  ([home-dir]
   (let [updf #(if (string? %) (.replace % "${zico.home}" home-dir))
         conf (->
                (zbu/read-config
                  zcfg/ZicoConf
                  (io/resource "zico/zico.conf")
                  (zbu/to-path (zbu/ensure-dir home-dir) "zico.conf"))
                (assoc :home-dir home-dir)
                (update-in [:backup :path] updf)
                (update-in [:zico-db :subname] updf)
                (update-in [:tstore :path] updf)
                (update-in [:log :main :path] updf))
         logs (-> conf :log :main)]
     (zbu/ensure-dir (-> conf :backup :path))
     (zbu/ensure-dir (-> conf :tstore :path))
     (zbu/ensure-dir (-> conf :log :main :path))
     (taoensso.timbre/merge-config!
       {:appenders {:rotor   (rotor-appender (assoc logs :path (str (:path logs) "/zico.log")))
                    :println {:enabled? false}}})
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
      (zbu/conf-reload-task #'reload (System/getProperty "zico.home") "zico.conf"))
    ; Set up Jetty container
    (System/setProperty "org.eclipse.jetty.server.Request.maxFormContentSize" (str (:max-form-size http)))
    (run-jetty zorka-main-handler http)
    (println "ZICO is up and running on: " (str (:ip http) ":" (:port http)))))

(defn -main [& args]
  (start-server))

