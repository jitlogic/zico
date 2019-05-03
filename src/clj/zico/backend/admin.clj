(ns zico.backend.admin
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.java.shell :as clsh]
    [taoensso.timbre :as log]
    [zico.backend.util :as zbu])
  (:import (java.lang.management ManagementFactory)))

(defn error
  [status reason & args]
  (log/error "ERROR: " reason ": " (str args))
  {:type :zico, :reason reason, :status status})

(defn- str-uptime [ms]
  (let [s (int (/ ms 1000)), m (int (/ s 60)), h (int (/ m 60)), d (int (/ h 24))]
    (str
      (if (> d 0) (str d "d ") "")
      (String/format "%02d:%02d:%02d" (object-array [(mod h 24) (mod m 60) (mod s 60)])))))

(defn system-info [{:keys [conf obj-store] :as _}]
  (let [runtime (ManagementFactory/getRuntimeMXBean)
        memory (ManagementFactory/getMemoryMXBean)]
    {:vm-version (.getVmVersion runtime)
     :vm-name    (.getVmName runtime)
     :uptime     (str-uptime (.getUptime runtime))
     :mem-used   (.getUsed (.getHeapMemoryUsage memory))
     :mem-max    (.getMax (.getHeapMemoryUsage memory))
     :home-dir   (:home-dir conf)
     :tstamps    {}                                         ; TODO timestamps
     }))

