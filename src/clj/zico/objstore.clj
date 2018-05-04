(ns zico.objstore
  "Object Store definitions and access functions."
  (:require
    [clojure.set :as cs]
    [zico.util :as zutl]
    [clojure.java.jdbc :as jdbc]
    [taoensso.timbre :as log])
  (:import (java.util Random)
           (java.util.concurrent.atomic AtomicLong)
           (org.apache.tomcat.dbcp.dbcp BasicDataSource)
           (org.flywaydb.core Flyway)))


(defonce SEQ-NUM (AtomicLong.))
(defonce NODE-NUM 1)
(defonce RAND-NUM (Random.))
(defonce RUN-ID (bit-and (.nextInt RAND-NUM) 0xffff))

(def RE-UUID #"(\p{XDigit}{8})-(\p{XDigit}{4})-(\p{XDigit}{4})-(\p{XDigit}{4})-(\p{XDigit}{12})")

; UUID format for database records
; 21c00000-OONN-UUUU-nnnn-Vttttttttttt
; t - timestamp (millisecond precision)
; V - UUID version (currently 0)
; n - sequential number (modulo 64k)
; N - node number
; U - user ID (one, not UUID, 0 for system user)
; O - object type
; x - random number


(defn gen-uuid
  ([oid uid]
   (gen-uuid oid uid RUN-ID))
  ([oid uid rid]
    (gen-uuid oid uid rid (.getAndIncrement SEQ-NUM)))
  ([oid uid rid seq-num]
   (let [t (zutl/cur-time)]
     (format
       "21c0%04x-%02x%02x-%04x-%04x-%012x"
       rid
       oid
       NODE-NUM
       uid
       seq-num
       (bit-and t 0xffffffffffff)
       ))))


(defn extract-uuid-seq [uuid]
  (or
    (when (string? uuid)
      (when-let [[_ _ _ _ seq _] (re-matches RE-UUID uuid)]
        (Long/parseLong seq 16)))
    0))


(def OBJ-TYPES
  {:ttype    0x01
   :app      0x02
   :env      0x03
   :attrdesc 0x04
   :host     0x05
   :hostattr 0x06
   :hostreg  0x07
   :user     0x08
   :groups   0x09
   :access   0x0a
   :props    0x0f
   })


(def OBJ-IDS (into {} (for [[idt _] OBJ-TYPES] {idt (AtomicLong.)})))


(defn last-seq-nums [data]
  (let [seq-map (group-by :class (vals data))]
    (into {}
          (for [[class recs] seq-map]
            {class (reduce max (map (comp extract-uuid-seq :uuid) recs))}))))


(defn setup-obj-ids [data]
  (doseq [[_ counter] OBJ-IDS] (.set counter 0))
  (doseq [[class seq-num] (last-seq-nums data)]
    (.set (OBJ-IDS class) seq-num)))


(defn jdbc-datasource [{:keys [subprotocol subname host port dbname user password classname] :as conf}]
  "Configures and returns pooled data source."
  (when-not (empty? conf)
    (Class/forName classname)
    (let [url (case subprotocol
                "h2" (str "jdbc:" subprotocol ":" subname)
                "mysql" (str "jdbc:" subprotocol "://" host ":" port "/" dbname)
                (throw (RuntimeException. "Invalid database driver. Only 'h2' and 'mysql' are allowed.")))]
      (log/info "Creating database connection pool to: " url)
      {:datasource
       (doto (BasicDataSource.)
         (.setDriverClassName classname)
         (.setUrl url)
         (.setUsername user)
         (.setPassword password)
         (.setMinIdle 5)
         (.setMaxIdle 20)
         (.setMaxOpenPreparedStatements 256)
         (.setTestOnBorrow true)
         (.setTestWhileIdle true)
         (.setTimeBetweenEvictionRunsMillis 60000)
         (.setValidationQuery "select 1 as t")
         (.setValidationQueryTimeout 2))})))


(defn jdbc-reconnect [old-conn old-conf new-conf]
  "Opens or reopens database connection. Closes old connection. If configuration didn't change, just returns old connection. "
  (if (or (not= old-conf new-conf) (nil? old-conn))
    (try
      (log/info "Database connection changed. Restarting connection.")
      (let [new-conn (jdbc-datasource new-conf)]
        (log/info "Database connection created.")
        (when old-conn
          (log/info "Old database connection closing delayed.")
          (future
            (Thread/sleep 10000)
            (.close old-conn)
            (log/info "Old database connection closed.")))
        new-conn)
      (catch Exception e
        (log/error e "Error reconfiguring database connection. Leaving old connection (if any) intact.")
        old-conn))
    (do
      (log/info "JDBC configuration not changed. Leaving old connection intact.")
      old-conn)))


(defn jdbc-migrate [{:keys [datasource] :as ds}]
  "Runs flyway migration. Returns datasource itself, so it can be used in threading macros."
  (doto (Flyway.)
    (.setDataSource datasource)
    (.setPlaceholderReplacement false)
    (.migrate))
  ds)

(defn jdbc-read-and-map [conn]
  "Reads configuration tables and maps them into "
  (into
    {}
    (for [table (keys OBJ-TYPES)]
      (into
        {}
        (for [r (jdbc/query conn [(str "select * from " (zutl/to-str table))])]
          {(:uuid r) (assoc r :class table)})))))


(defprotocol ObjectStore
  (put-obj   [this obj])
  (get-obj   [this uuid])
  (del-obj   [this uuid])
  (find-obj  [this opts])
  (refresh    [this]))


(defprotocol ManagedStore
  (backup [this opts])
  (restore [this path name]))


(defn- obj-matcher [conditions]
  (fn [[uuid obj]]
    (every?
      #(let [[k cv] %]
         (cond
           (set? cv)
           (let [ov (k obj)]
             (when (set? ov)
               (not (empty? (cs/intersection cv ov)))))
           :else
           (= (k obj) cv)))
      conditions)))


; TODO implement non-caching object store implementation
(defn jdbc-caching-store [zico-db]
  (let [data (atom {})]
    (reify
      ObjectStore
      (put-obj [_ {:keys [uuid class] :as obj}]
        (when-not (and (keyword? class) (OBJ-TYPES class))
          (throw (RuntimeException. (str "Trying to save invalid object: " obj))))
        (let [seqnum (.incrementAndGet (OBJ-IDS class))
              uuid (or uuid (gen-uuid (OBJ-TYPES class) 0 0 seqnum))]
          (if (contains? obj :uuid)
            (jdbc/update! zico-db class (dissoc obj :uuid :class) ["uuid = ?" uuid])
            (jdbc/insert! zico-db class (dissoc (assoc obj :uuid uuid) :class)))
          (swap! data assoc uuid (assoc obj :uuid uuid))
          (assoc obj :uuid uuid)))
      (get-obj [_ uuid]
        (let [obj (get @data uuid)] obj))
      (del-obj [_ uuid]
        (let [r (get @data uuid)]
          (when r
            (jdbc/delete! zico-db (:class r) ["uuid = ?" uuid])
            (swap! data dissoc uuid))))
      (find-obj [_ opts]
        (let [data @data]
          (map first (filter (obj-matcher opts) data))))
      (refresh [_]
        (reset! data (jdbc-read-and-map zico-db))
        (setup-obj-ids @data))
      ManagedStore
      (backup [_ path]
        (try
          (let [fname (str "conf-" (zutl/str-time-yymmdd-hhmmss-sss) ".sql")
                fpath (str (or path "data/backup") "/" fname)]
            (log/info "Backing up configuration to: " fpath)
            (doall (jdbc/query zico-db [(str "script drop to '" fpath "'")])))
          (catch Exception e
            (log/error e "Error backing up"))))
      (restore [_ path fname]
        (let [fpath (str (or path "data/backup") "/" fname)]
          (doall (jdbc/db-do-commands zico-db [(str "runscript from '" fpath "'")]))))
      )))


(defn find-and-get [obj-store opts]
  (for [uuid (find-obj obj-store opts)]
    (get-obj obj-store uuid)))


(defn find-and-get-1 [obj-store opts]
  (first (find-and-get obj-store opts)))


(defn get-by-uuid-or-prefix [obj-store class uuid prefix]
  (let [rslt (or (get-obj obj-store uuid)
                 (first (find-and-get obj-store {:name prefix})))]
    (if (= class (:class rslt)) rslt)))


