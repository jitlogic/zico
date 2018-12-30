(ns zico.objstore
  "Object Store definitions and access functions."
  (:require
    [clojure.set :as cs]
    [zico.util :as zutl]
    [clojure.java.jdbc :as jdbc]
    [taoensso.timbre :as log])
  (:import (org.apache.tomcat.dbcp.dbcp BasicDataSource)
           (org.flywaydb.core Flyway)
           (java.io File)))

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


; TODO move object class to dedicated parameter, so coding will be easier around schema coercions
(defprotocol ObjectStore
  (put-obj     [this obj])
  (get-obj     [this attrs])
  (del-obj     [this attrs])
  (find-obj    [this attrs]))


(defprotocol ManagedStore
  (backup [this opts])
  (restore [this path name]))


(defn jdbc-store [zico-db]
  (reify
    ObjectStore
    (put-obj [_ {:keys [class id] :as obj}]
      (if (contains? obj :id)
        (do (jdbc/update! zico-db class (dissoc obj :id :class) ["id = ?" id]) obj)
        (let [x (jdbc/insert! zico-db class (dissoc (assoc obj :id id) :class))]
          (assoc obj :id (second (first (first x)))))))
    (get-obj [_ {:keys [class id]}]
      (when-let [obj (first (jdbc/query zico-db [(str " select * from " (zutl/to-str class) " where id = ?") id]))]
        (assoc obj :class class)))
    (del-obj [_ {:keys [class id]}]
      (jdbc/delete! zico-db class ["id = ?" id]))
    (find-obj [_ {:keys [class] :as attrs}]
      (let [qs (for [[k v] (dissoc attrs :class)] [(str (name k) " = ? ") v])
            qa (clojure.string/join " and " (map first qs))
            q0 (if (empty? qa)
                 (str "select * from " (name class))
                 (str "select * from " (name class) " where " qa))]
        (for [r (jdbc/query zico-db (into [q0] (doall (map second qs))))]
          (:id r))))
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
    ))


(defn find-and-get [obj-store {:keys [class] :as attrs}]
  (for [id (find-obj obj-store attrs)]
    (get-obj obj-store {:class class, :id id})))


(defn find-and-get-1 [obj-store attrs]
  (first (find-and-get obj-store attrs)))


(defn merge-initial-data [object-store class overwrite data]
  (doseq [{:keys [name] :as d} data,
          :let [r (find-and-get-1 object-store {:class class, :name name})]]
    (cond
      (nil? r) (put-obj object-store (assoc d :class class))
      overwrite (put-obj object-store (merge r d))
      :else nil)))


(defn slurp-init-file [^String homedir class]
  (try
    (let [f (File. (File. homedir "init") (str (name class) ".edn"))]
      (if (.exists f)
        (read-string (slurp f))))
    (catch Exception e
      (log/warn e "Cannot read init file for class: " class)
      nil)))

(defn slurp-init-classpath [class]
  (read-string
    (slurp (clojure.java.io/resource  (str "db/init/" (name class) ".edn")))))

(def INIT-CLASSES [:app :env :hostreg :ttype :user])

(defn load-initial-data [obj-store {:keys [init-source init-mode]} homedir]
  (doseq [class INIT-CLASSES
          :let [data (concat
                       (if (#{:internal :all} init-source) (slurp-init-classpath class))
                       (if (#{:external :all} init-source) (slurp-init-file homedir class)))]]
    (when-not (= init-mode :skip)
      (merge-initial-data obj-store class (= init-mode :overwrite) data))))
