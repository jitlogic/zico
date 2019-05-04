(ns zico.test-util
  (:require
    [zico.main :as zsvr]
    [zico.util :as zu]
    [clojure.java.io :as io])
  (:import
    (java.io File)
    (io.zorka.tdb.store RotatingTraceStore)))

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

(defn cleanup-fixture [app-state]
  (when-let [^RotatingTraceStore ts (-> app-state :trace-store)] (.close ts)))

(defn zorka-integ-fixture [f]
  (let [root-path "/tmp/zico-collector-test"
        conf (zu/recursive-merge
               (aero.core/read-config (io/resource "zico/zico.edn"))
               {:home-dir root-path
                :tstore {:path (zu/to-path root-path "data")
                         :maint-threads 0}
                :log {:console-level :info}})
        _ (rm-rf (File. "/tmp/zico-test"))
        app-state (zsvr/new-app-state {} conf)
        root (File. root-path)]
    (with-redefs [zu/cur-time cur-time-mock]
      (reset! cur-time-val 100)
      (rm-rf (File. root-path))
      (.mkdirs (File. root "data/trace"))
      (binding [zsvr/zorka-app-state app-state
                zico  (:main-handler app-state)
                trace-store (:tstore app-state)
                *root-path* root-path]
        (f)
        (cleanup-fixture app-state)))))

