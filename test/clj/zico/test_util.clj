(ns zico.test-util
  (:require
    [zico.server.main :as zsvr]
    [clojure.java.jdbc :as jdbc]
    [zico.backend.util :as zbu]
    [clojure.java.io :as io])
  (:import
    (java.io File)
    (org.apache.tomcat.dbcp.dbcp BasicDataSource)))


(def cur-time-val (atom 100))

(defn time-travel [t]
  (reset! cur-time-val t))

(def ^:dynamic *root-path* nil)
(def ^:dynamic zorka nil)
(def ^:dynamic zorkav nil)
(def ^:dynamic obj-store nil)
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


(defn cleanup-fixture [app-state]
  (when-let [^BasicDataSource db (-> app-state :zorka-db :datasource)] (.close db))
  ;(when-let [^RotatingTraceStore ts (-> app-state :trace-store)] (.close ts))
  )

(def DEFAULT-CONF
  (read-string (slurp (io/resource "zico/zico.edn"))))

(defn zorka-integ-fixture [f]
  (let [conf (zbu/recursive-merge DEFAULT-CONF (read-string (slurp "testdata/zorka.conf")))
        _ (rm-rf (File. "/tmp/zico-test"))
        app-state (zsvr/new-app-state {} conf)
        root-path "/tmp/zico-test", root (File. root-path)]
    (taoensso.timbre/set-level! :error)
    (with-redefs [zbu/cur-time cur-time-mock]
      (reset! cur-time-val 100)
      (rm-rf (File. root-path))
      (.mkdirs (File. root "data/trace"))
      (jdbc/delete! (:zico-db app-state) :host ["id is not null"])
      (binding [zsvr/zorka-app-state app-state
                zorka  (:main-handler app-state)
                obj-store (:obj-store app-state)
                trace-store (:tstore app-state)]
        (f)
        (cleanup-fixture app-state)))))

