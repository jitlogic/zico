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
            [zico.objstore :as zobj]
            [ring.util.servlet :as servlet])
  (:gen-class)
  (:import (org.slf4j.impl ZorkaLoggerFactory ConsoleTrapper ZorkaTrapper ZorkaLogLevel)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (java.security KeyStore)
           (org.eclipse.jetty.server HttpConfiguration SecureRequestCustomizer ConnectionFactory HttpConnectionFactory SslConnectionFactory Server ServerConnector Request)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.http HttpVersion)
           (org.eclipse.jetty.server.handler AbstractHandler)))


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
(defonce ^:dynamic jetty-server (atom nil))
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


(defn ^SslContextFactory ssl-context-factory [options]
  (let [context (SslContextFactory.)]
    (if (string? (options :keystore))
      (.setKeyStorePath context (options :keystore))
      (.setKeyStore context ^KeyStore (options :keystore)))
    (.setKeyStorePassword context (options :keypass))
    (cond
      (string? (options :truststore))
      (.setTrustStore context ^String (options :truststore))
      (instance? KeyStore (options :truststore))
      (.setTrustStore context ^KeyStore (options :truststore)))
    (when (options :trustpass)
      (.setTrustStorePassword context (options :trustpass)))
    (when (options :include-ciphers)
      (.setIncludeCipherSuites context (into-array String (options :include-ciphers))))
    (when (options :exclude-ciphers)
      (.setExcludeCipherSuites context (into-array String (options :exclude-ciphers))))
    (when (options :include-protocols)
      (.setIncludeProtocols context (into-array String (options :include-protocols))))
    (when (options :exclude-protocols)
      (.setExcludeProtocols context (into-array String (options :exclude-protocols))))
    (case (options :client-auth)
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn jetty-https-configuration [options]
  (doto
    (HttpConfiguration.)
    (.setSecureScheme "https")
    (.setSecurePort (:port options 8443))
    (.setRequestHeaderSize (:request-header-size options 65536))
    (.setResponseHeaderSize (:response-header-size options 65536))
    (.setOutputBufferSize (:output-buffer-size options 262144))
    (.setSendServerVersion false)
    (.setSendDateHeader true)
    (.addCustomizer (SecureRequestCustomizer.))
    ))

(defn ^QueuedThreadPool jetty-thread-pool [options]
  (doto
    (QueuedThreadPool.)
    (.setMaxThreads (:max-threads options 50))
    (.setDaemon false)))

(defn jetty-proxy-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map  (servlet/build-request-map request)
            response-map (handler request-map)]
        (when response-map
          (servlet/update-servlet-response response response-map)
          (.setHandled base-request true))))))

(defn jetty-server-instance [options handler]
  (let [server (doto
                 (Server. (jetty-thread-pool options))
                 (.setDumpAfterStart false)
                 (.setDumpBeforeStop false)
                 (.setStopAtShutdown true)
                 (.setHandler handler))
        ssl-cf (SslConnectionFactory. (ssl-context-factory options) (.asString HttpVersion/HTTP_1_1))
        http-cf (HttpConnectionFactory. (jetty-https-configuration options))
        cfs (into-array ConnectionFactory [ssl-cf http-cf])
        https-conn (ServerConnector. server nil nil nil -1 -1 cfs)]
    (.setPort https-conn (:port options 8443))
    (.addConnector server https-conn)
    server))

(defn ^Server run-jetty-container
  "Start a Jetty webserver to serve the given handler according to the supplied options:

  :http-conf      - options for plaintext connector;
  :https-conf     - options for SSL connector;
  :join?          - blocks the thread until server ends (defaults to true)
  :daemon?        - use daemon threads (defaults to false)
  :max-threads    - the maximum number of threads to use (default 50)
  :min-threads    - the minimum number of threads to use (default 8)
  :max-queued     - the maximum number of requests to queue (default unbounded)

  HTTP configurations have the following options:
  :port - listen port;
  :host - listen address;
  :max-idle-time - max time unused connection will be kept open;
  :request-header-size - max size of request header;
  :request-buffer-size - request buffer size;
  :keystore, :keypass, :truststore, :trustpass - keystore and trust store for SSL communication;
  :client-auth - set to :need or :want if client authentication is needed/wanted;
  :include-ciphers, :exclude-ciphers - include/exclude SSL ciphers (list of strings);
  :include-protocols, :exclude-protocols - include/exclude SSL protocols (list of strings);
  "
  [handler options]
  (System/setProperty "org.eclipse.jetty.ssl.password" (:keypass options "changeit"))
  (doto
    (jetty-server-instance options (jetty-proxy-handler handler))
    (.start)
    ))

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
  (when-let [j @jetty-server]
    (.stop j)
    (reset! jetty-server nil))
  (when-let [cf @conf-autoreload-f]
    (future-cancel cf)
    (reset! conf-autoreload-f nil)))


(defn start-server []
  (stop-server)
  (reload)
  (let [{:keys [http-conf https-conf]} (:conf zorka-app-state)]
    (reset!
      conf-autoreload-f
      (zutl/conf-reload-task
        #'reload
        (System/getProperty "zico.home")
        "zico.conf"))
    (when (:enabled? https-conf)
      (reset!
        jetty-server
        (run-jetty-container
          #'zorka-main-handler
          (merge https-conf {:daemon? true})))
      (println "HTTPS listening on port" (:port https-conf)))
    (when (:enabled? http-conf)
      (reset!
        stop-f
        (hsv/run-server
          #'zorka-main-handler
          (merge DEFAULT-HTTP-CONF http-conf)))
      (println "HTTP on port" (:port http-conf)))
    (println "ZICO is up and running.")))


(defn -main [& args]
  (start-server))

