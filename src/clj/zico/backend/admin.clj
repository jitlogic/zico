(ns zico.backend.admin
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.java.shell :as clsh]
    [taoensso.timbre :as log]
    [zico.backend.util :as zbu])
  (:import (java.io File)
           (java.lang.management ManagementFactory)))

(def RE-BKPF #"([0-9a-fA-F]{6})\.sql")

(defn error
  [status reason & args]
  (log/error "ERROR: " reason ": " (str args))
  {:type :zico, :reason reason, :status status})

(defn- list-backups [^File bdir limit]
  (let [files (for [^String fname (.list bdir)
                    :let [f (File. bdir fname), [_ id] (re-matches RE-BKPF fname)]
                    :when (and (.isFile f) (some? id))]
                {:id (Integer/parseInt id 16), :tstamp (.lastModified f), :size (.length f)})]
    (take limit (reverse (sort-by :id files)))))


(defn backup-dir [{:keys [conf]}]
  (let [^String bpath (-> conf :backup :path)
        bdir (if (.startsWith "/" bpath) (File. bpath) (File. ^String (:home-dir conf) bpath))]
    (when-not (.exists bdir) (.mkdirs bdir))
    bdir))


(defn backup-list [app-state  _]
  (for [{:keys [id tstamp size]} (list-backups (backup-dir app-state) 100)]
    {:id id, :tstamp (zbu/str-time-yymmdd-hhmmss-sss tstamp), :size size}))


(defn backup-new [app-state]
  (let [bdir (backup-dir app-state)
        last-id (:id (first (list-backups bdir 1)))
        id (if (some? last-id) (inc last-id) 1)]
    [id (File. ^File bdir (String/format "%06x.sql", (object-array [id])))]))


(defmulti backup
  (fn [{:keys [conf]} _]
    (-> conf :zico-db :subprotocol)))


(defmethod backup "h2" [{:keys [zico-db] :as app-state}  _]
  (let [[id outf] (backup-new app-state)]
    (jdbc/query zico-db ["SCRIPT DROP TO ?" (.getPath outf)])
    {:id id, :tstamp (zbu/str-time-yymmdd-hhmmss-sss (.lastModified outf)), :size (.length outf)}))


(defmethod backup "mysql" [{{{:keys [mysqldump dbname host port user password]} :zico-db} :conf :as app-state} _]
  (let [[id outf] (backup-new app-state)]
    (let [r (clsh/sh mysqldump dbname "-h" host (str "-P" port) "-r" (.getPath outf) "-u" user (str "-p" password))]
      (case (:exit r)
        0 {:id id, :tstamp (zbu/str-time-yymmdd-hhmmss-sss (.lastModified outf)), :size (.length outf)}
        (do
          (log/error "Error performing backup" r)
          (error 500 "Error performing backup."))))))


(defmethod backup :default [_ _]
  (error 500 "Backup not supported for this database backend."))


(defmulti restore
  (fn [{:keys [conf]} _]
    (-> conf :zico-db :subprotocol)))


(defmethod restore "h2" [{:keys [zico-db] :as app-state} id]
  (let [bdir (backup-dir app-state)
        bkpf (File. ^File bdir (String/format "%06x.sql", (object-array [id])))]
    (cond
      (not bkpf) (error 400 "Invalid ID")
      (not (.canRead bkpf)) (error 404 "No such backup")
      :else
      (try
        (jdbc/execute! zico-db ["RUNSCRIPT FROM ?" (.getPath bkpf)])
        {:id id, :tstamp (zbu/str-time-yymmdd-hhmmss-sss (.lastModified bkpf)), :size (.length bkpf)}
        (catch Exception e
          (log/error "Error restoring backup" e)
          (error 500 "Error restoring backup."))))))


(defmethod restore "mysql" [{{{:keys [mysql dbname host port user password]} :zico-db} :conf :as app-state}
                            {{:keys [id]} :params}]
  (let [bdir (backup-dir app-state)
        bkpf (File. ^File bdir (String/format "%06x.sql", (object-array [id])))]
    (try
      (cond
        (not bkpf) (error 400 "Invalid ID")
        (not (.canRead bkpf)) (404 "No such backup")
        :else
        (let [r (clsh/sh  mysql dbname "-u" user (str "-P" port) "-h" host (str "-p" password) "-e" (str "source " bkpf))]
          (case (:exit r)
            0 {:id id, :tstamp (zbu/str-time-yymmdd-hhmmss-sss (.lastModified bkpf)), :size (.length bkpf)}
            (do
              (log/error "Error restoring backup." r)
              (error 500 "Error restoring backup.")))))
      (catch Exception e
        (log/error "Error performing backup." e)
        (error 500 "Error performing backup.")))))


(defmethod restore :default [_ _]
  (error 400 "Backup not supported for this database backend."))


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

