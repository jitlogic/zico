(ns zico.util
  (:require [taoensso.timbre :as log]
            [clj-time.coerce :as ctco]
            [clj-time.format :as ctfo]
            [slingshot.slingshot :refer [try+]]
            [hiccup.page :refer [include-js include-css html5]]
            [clojure.string :as cs])
  (:import (java.io File)
           (java.util UUID Properties HashMap)
           (java.util.concurrent ExecutorService TimeUnit)
           (io.zorka.tdb.util TrivialExecutor)
           (java.net Socket)))

(def ALPHA-STR "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
(def SALT-STR (str ALPHA-STR "!@#$%^&*!@#$%%^^&*"))


(defn random-string
  "Generates random string of alphanumeric characters of given length."
  ([len]
   (random-string len ALPHA-STR))
  ([len s]
   (apply str (for [_ (range len)] (rand-nth s)))))


(defn cur-time
  ([] (cur-time 0))
  ([offs] (+ offs (System/currentTimeMillis))))


(defn to-path [home-dir path]
  (if (.startsWith path "/") path (str home-dir "/" path)))


(defn tst [t]
  "Generates timestamp from (any)."
  (cond
    (nil? t) nil
    (re-matches #"\d\d" t) (ctco/to-date-time (str t ":00:00"))
    (re-matches #"\d\d:\d\d" t) (ctco/to-date-time (str t ":00"))
    (re-matches #"\d\d:\d\d:\d\d" t) (ctco/to-date-time t)
    (re-matches #"\d\d \d\d:\d\d" t) (ctco/to-date-time (str "1971-01-" t ":00"))
    (re-matches #"\d\d \d\d:\d\d:\d\d" t) (ctco/to-date-time (str "1971-01-" t))
    (re-matches #"\d\d\d\d-\d\d-\d\d" t) (ctco/to-date-time (str t " 00:00:00"))
    (re-matches #"\d\d\d\d-\d\d-\d\d \d\d:\d\d" t) (ctco/to-date-time (str t ":00"))
    (re-matches #"\d\d\d\d-\d\d-\d\d \d\d:\d\d:\d\d" t) (ctco/to-date-time t)
    (re-matches #"\d{8}T\d{6}Z" t) (ctco/to-date-time t)
    :else (throw (RuntimeException. (str "Invalid date time format '" t "'")))))


(defn to-java-time [t]
  (ctco/to-long (tst t)))


(defn to-unix-time [t]
  (/ (ctco/to-long (tst t)) 1000))


(defn str-time-yymmdd-hhmmss-sss
  ([]
   (str-time-yymmdd-hhmmss-sss (cur-time)))
  ([t]
   (ctfo/unparse
     (:date-hour-minute-second-ms ctfo/formatters)
     (ctco/from-long t))))


(defn conf-reload-task [reload-fn home & files]
  (log/info "Starting automatic configuration reload task ..." reload-fn)
  (future
    (loop [tst1 (vec (for [f files] (.lastModified (File. ^String home ^String f))))]
      (Thread/sleep 5000)
      (let [tst2 (vec (for [f files] (.lastModified (File. ^String home ^String f))))]
        (try
          (when-not (= tst1 tst2)
            (log/info "Configuration change detected. Reloading ...")
            (reload-fn)
            (log/info "Configuration reloaded succesfully."))
          (catch Throwable e
            (log/error "Error reloading configuration: " e)
            (.printStackTrace e)))
        (recur tst2)))))


(defn index-of
  ([item coll]
   (index-of item coll 0))
  ([item coll idx]
   (cond
     (empty? coll) nil
     (= item (first coll)) idx
     :else (recur item (rest coll) (inc idx)))))


(defn to-str [v]
  (cond
    (string? v) v
    (or (keyword? v) (symbol? v)) (name v)
    :else (str v)))


(defn scan-files [^File f re-fname]
  (if (and (.exists f) (.isDirectory f))
    (apply
      concat
      (for [nf (.listFiles f)
            :when (or (and (.isFile nf) (.canRead nf) (re-matches re-fname (.getName nf))) (.isDirectory nf))]
        (if (.isDirectory nf)
          (scan-files nf re-fname)
          [nf])))))


(defn scan-file-tstamps [path]
  (let [files (scan-files (File. ^String path) #".*\.conf")]
    (into {} (for [f files]
               {(.getName f) (.lastModified f)}))))


(defn partition-split [n coll]
  (when coll
    (if (<= (count coll) n)
      (cons coll nil)
      (lazy-seq
        (cons
          (take n coll)
          (partition-split n (drop n coll)))))))


(defn daemon-thread [name f]
  (doto
    (Thread. ^Runnable f)
    (.setName name)
    (.setDaemon true)
    (.start)))


(defn conf-to-props
  ([conf]
   (conf-to-props conf "" (Properties.)))
  ([conf prefix]
    (conf-to-props conf prefix (Properties.)))
  ([conf prefix prop]
   (doseq [[k v] conf]
     (cond
       (map? v) (conf-to-props v (str prefix (to-str k) ".") prop)
       (some? v) (.setProperty prop (str prefix (to-str k)) (to-str v))))
    prop))


(defn simple-executor [new-nthreads old-nthreads ^ExecutorService old-executor]
  (if (or (nil? old-executor) (not= old-nthreads new-nthreads))
    (let [new-executor (TrivialExecutor/newExecutor new-nthreads)]
      (when (instance? ExecutorService old-executor)
        (.execute new-executor
          (fn []
            (Thread/sleep 12000)
            (log/info "Shutting down old indexer thread pool ...")
            (.shutdown old-executor)
            (log/info "Waiting for old thread pool to finish ...")
            (.awaitTermination old-executor 300 TimeUnit/SECONDS))))
      new-executor)
    old-executor))


(defn filter-unique
  ([seq]
    (filter-unique seq #{}))
  ([[obj & seq] acc]
    (cond
      (nil? obj) nil
      (acc obj) (recur seq acc)
      :else (lazy-seq (cons obj (filter-unique seq (conj acc obj)))))))


(defn java-hash-map [& {:as data}]
  (let [rslt (HashMap.)]
    (doseq [[k v] data] (.put rslt k v))
    rslt))


(defn tcp-request [^String host ^Integer port ^String text]
  (try+
    (with-open [sock (Socket. host port)]
      (spit (.getOutputStream sock) text)
      (slurp (.getInputStream sock)))
    (catch Object e
      (log/error e (str "Error performing TCP request to " host ":" port))
      nil)))


(defn recursive-merge [map1 map2]
  "Recursive merge"
  (if (and (map? map1) (map? map2))
    (merge-with recursive-merge map1 map2)
    map2))


(defn rekey-map [m]
  (if (map? m)
    (into {}
          (for [[k v] m]
            {(keyword k) v}))
    m))


(defn rest-result
  ([data] {:status 200, :body {:type :rest, :data data}})
  ([data status] (assoc (rest-result data) :status status)))


(defn rest-error
  ([msg] {:status 500, :body {:type :rest, :data {:error msg}}})
  ([msg status] (assoc (rest-error msg) :status status)))


(defn rest-msg
  ([msg] {:status 200, :body {:type :rest, :data {:msg msg}}})
  ([msg status] (assoc (rest-msg msg) :status status)))


(defmacro log-rest-error [msg status & args]
  `(do
     (log/error ~msg ~@args)
     (rest-error ~msg ~status)))

(def RE-METHOD-DESC #"(.*)\s+(.*)\.(.+)\.([^\(]+)(\(.*\))")


(defn parse-method-str [s]
  (when s
    (when-let [[_ r p c m a] (re-matches RE-METHOD-DESC s)]
      (let [cs (.split c "\\." 0), cl (alength cs)]
        {:result r, :package p, :class c, :method m, :args a}))))



(defn render-page [& content]
  (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport"
             :content "width=device-width, height=device-height, initial-scale=1, maximum-scale=1, user-scalable=no"}]
     (include-css (str "/css/zico" (if (System/getProperty "zico.dev.mode") ".css" ".min.css")))]
    [:body {:class "body-container"}
     content]))

