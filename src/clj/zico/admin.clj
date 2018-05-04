(ns zico.admin
  (:require
    [clojure.java.jdbc :as jdbc]
    [clojure.java.shell :as clsh]
    [zico.objstore :as zobj]
    [zico.util :as zutl]
    [taoensso.timbre :as log])
  (:import (java.io File)
           (java.lang.management ManagementFactory)))

(def RE-BKPF #"([0-9a-fA-F]{6})\.sql")


(defn- list-backups [^File bdir limit]
  (let [files (for [^String fname (.list bdir)
                    :let [f (File. bdir fname), [_ id] (re-matches RE-BKPF fname)]
                    :when (and (.isFile f) (some? id))]
                {:id (Integer/parseInt id 16), :tstamp (.lastModified f), :size (.length f)})]
    (take limit (reverse (sort-by :id files)))))


(defn backup-dir [{:keys [conf]}]
  (let [^String bpath (-> conf :backup-conf :path)
        bdir (if (.startsWith "/" bpath) (File. bpath) (File. ^String (:home-dir conf) bpath))]
    (when-not (.exists bdir) (.mkdirs bdir))
    bdir))


(defn backup-list [app-state  _]
  (zutl/rest-result
    (for [{:keys [id tstamp size]} (list-backups (backup-dir app-state) 100)]
      {:id id, :tstamp (zutl/str-time-yymmdd-hhmmss-sss tstamp), :size size})))


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
    (try
      (jdbc/query zico-db ["SCRIPT DROP TO ?" (.getPath outf)])
      (zutl/rest-result
        {:id id, :tstamp (zutl/str-time-yymmdd-hhmmss-sss (.lastModified outf))})
      (catch Exception e
        (log/error "Error performing backup:" e)
        (zutl/rest-error "Error performing backup.")))))


(defmethod backup "mysql" [{{{:keys [mysqldump dbname host port user password]} :zico-db} :conf :as app-state} _]
  (let [[id outf] (backup-new app-state)]
    (try
      (let [r (clsh/sh mysqldump dbname "-h" host (str "-P" port) "-r" (.getPath outf) "-u" user (str "-p" password))]
        (case (:exit r)
          0 (zutl/rest-result
              {:id id, :tstamp (zutl/str-time-yymmdd-hhmmss-sss (.lastModified outf))})
          (do
            (log/error "Error performing backup" r)
            (zutl/rest-error "Error performing backup."))))
      (catch Exception e
        (log/error "Error performing backup:" e)
        (zutl/rest-error "Error performing backup.")))))


(defmethod backup :default [_ _]
  (zutl/rest-error "Backup not supported for this database backend." 400))


(defmulti restore
  (fn [{:keys [conf]} _]
    (-> conf :zico-db :subprotocol)))


(defmethod restore "h2" [{:keys [obj-store zico-db] :as app-state} {{:keys [id]} :params}]
  (let [bdir (backup-dir app-state)
        bkpf (if (re-matches #"[0-9]+" id) (File. ^File bdir (String/format "%06x.sql", (object-array [(Integer/parseInt id)]))))]
    (cond
      (not bkpf) (zutl/rest-error "Invalid ID" 400)
      (not (.canRead bkpf)) (zutl/rest-error "No such backup" 404)
      :else
      (try
        (jdbc/execute! zico-db ["RUNSCRIPT FROM ?" (.getPath bkpf)])
        (zobj/refresh obj-store)
        (zutl/rest-result
          {:id id})
        (catch Exception e
          (log/error "Error restoring backup" e)
          (zutl/rest-error "Error restoring backup."))))))


(defmethod restore "mysql" [{{{:keys [mysql dbname host port user password]} :zico-db} :conf :as app-state}
                            {{:keys [id]} :params}]
  (let [bdir (backup-dir app-state)
        bkpf (if (re-matches #"[0-9]+" id) (File. ^File bdir (String/format "%06x.sql", (object-array [(Integer/parseInt id)]))))]
    (try
      (cond
        (not bkpf) (zutl/rest-error "Invalid ID" 400)
        (not (.canRead bkpf)) (zutl/rest-error "No such backup" 404)
        :else
        (let [r (clsh/sh  mysql dbname "-u" user (str "-P" port) "-h" host (str "-p" password) "-e" (str "source " bkpf))]
          (case (:exit r)
            0 (zutl/rest-result {:id id})
            (do
              (log/error "Error restoring backup." r)
              (zutl/rest-error "Error restoring backup.")))))
      (catch Exception e
        (log/error "Error performing backup." e)
        (zutl/rest-error "Error performing backup.")))))


(defmethod restore :default [_ _]
  (zutl/rest-error "Backup not supported for this database backend." 400))


(defn- str-uptime [ms]
  (let [s (int (/ ms 1000)), m (int (/ s 60)), h (int (/ m 60)), d (int (/ h 24))]
    (str
      (if (> d 0) (str d "d ") "")
      (String/format "%02d:%02d:%02d" (object-array [(mod h 24) (mod m 60) (mod s 60)])))))


(defn system-info [{:keys [conf] :as _} _]
  (let [runtime (ManagementFactory/getRuntimeMXBean)
        memory (ManagementFactory/getMemoryMXBean)]
    (zutl/rest-result
      {:vm-version (.getVmVersion runtime)
       :vm-name    (.getVmName runtime)
       :uptime     (str-uptime (.getUptime runtime))
       :mem-used   (.getUsed (.getHeapMemoryUsage memory))
       :mem-max    (.getMax (.getHeapMemoryUsage memory))
       :home-dir   (:home-dir conf)
       })))

