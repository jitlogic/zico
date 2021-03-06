(ns zico.util
  (:require
    [aero.core :as aero]
    [hiccup.page :refer [html5 include-css]]
    [schema.core :as s]
    [clj-time.coerce :as ctco]
    [clj-time.format :as ctfo]
    [clojure.tools.logging :as log]
    [clojure.java.io :as io])
  (:import
    (java.io File)
    (java.util Properties Base64)
    (java.time LocalDateTime OffsetDateTime)
    (java.security MessageDigest)
    (java.time.format DateTimeFormatter)))


(def DEV-MODE (.equalsIgnoreCase "true" (System/getProperty "zico.dev.mode")))


(defn cur-time
  ([] (cur-time 0))
  ([offs] (+ offs (System/currentTimeMillis))))


(defn to-path [home-dir path]
  (if (.startsWith path "/") path (str home-dir "/" path)))


(defn sleep [interval]
  (try
    (Thread/sleep interval)
    (catch InterruptedException _
      (log/warn "InterruptedException in store maintenance thread."))))


(defn conf-reload-task [reload-fn home & files]
  (log/info "Starting automatic configuration reload task ..." reload-fn)
  (future
    (loop [tst1 (vec (for [f files] (.lastModified (File. ^String home ^String f))))]
      (sleep 5000)
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


(defn ensure-dir [^String path]
  (let [f (File. path)]
    (cond
      (not (.exists f)) (if (.mkdirs f) (.getPath f) (throw (RuntimeException. (str "Cannot create directory: " f))))
      (.isDirectory f) (.getPath f)
      :else (throw (RuntimeException. (str "Not a directory: " f))))))


(defn partition-split [n coll]
  (when coll
    (if (<= (count coll) n)
      (cons coll nil)
      (lazy-seq
        (cons
          (take n coll)
          (partition-split n (drop n coll)))))))


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


(defn filter-unique
  ([seq]
   (filter-unique identity seq))
  ([ufn seq]
   (filter-unique ufn seq #{}))
  ([ufn [obj & seq] acc]
   (let [k (ufn obj)]
     (cond
       (nil? obj) nil
       (acc k) (recur ufn seq acc)
       :else (lazy-seq (cons obj (filter-unique ufn seq (conj acc k))))))))


(defn recursive-merge [map1 map2]
  "Recursive merge"
  (if (and (map? map1) (map? map2))
    (merge-with recursive-merge map1 map2)
    map2))


(defn read-config [schema & sources]
  (let [parts (for [s sources :when (or (not (string? s)) (.canRead (File. ^String s)))] s)
        cfg (reduce recursive-merge (map aero/read-config parts))]
    (s/validate schema cfg)
    cfg))


(def ALPHA-STR "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")


(defn random-string
  "Generates random string of alphanumeric characters of given length."
  ([len]
   (random-string len ALPHA-STR))
  ([len s]
   (apply str (for [_ (range len)] (rand-nth s)))))


(defn str-time-yymmdd-hhmmss-sss
  ([]
   (str-time-yymmdd-hhmmss-sss (cur-time)))
  ([t]
   (ctfo/unparse
     (:date-hour-minute-second-ms ctfo/formatters)
     (ctco/from-long t))))


(defn millis->iso-time [t]
  (.format DateTimeFormatter/ISO_LOCAL_DATE_TIME (LocalDateTime/ofEpochSecond (/ t 1000), 0, (.getOffset (OffsetDateTime/now)))))


(def RE-TST #"(\d{4})(\d\d)(\d\d)T(\d\d)(\d\d)(\d\d)Z?")


(defn iso-time->millis [s]
  (when-let [[_ y m d hh mm ss] (when (string? s) (re-matches RE-TST s))]
    (->
      (LocalDateTime/of
        (Integer/parseInt y) (Integer/parseInt m) (Integer/parseInt d)
        (Integer/parseInt hh) (Integer/parseInt mm) (Integer/parseInt ss))
      (.atOffset (.getOffset (OffsetDateTime/now)))
      .toInstant
      .toEpochMilli)))


(defn render-page [& content]
  (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport"
             :content "width=device-width, height=device-height, initial-scale=1, maximum-scale=1, user-scalable=no"}]
     (include-css (str "/css/zico" (if (System/getProperty "zico.dev.mode") ".css" ".min.css")))]
    [:body {:class "body-container"}
     content]))

(defn keywordize
  ([m]
   (cond
     (map? m)
     (into {}
       (for [[k v] m :let [k (keyword k)]]
         {(keyword k) (keywordize v)}))
     :else m)))

(defn group-map [coll]
  (let [m (group-by first coll)]
    (into {} (for [[k v] m] {k (if (not= 1 (count v)) (map second v) (second (first v)))}))))

(defn without-nil-vals [m]
  (into {} (for [[k v] m :when (some? v)] {k v})))

(defn b64enc [b]
  (when b (.encodeToString (Base64/getEncoder) b)))

(defn b64dec [^String s]
  (when s (.decode (Base64/getDecoder) s)))


(def RE-BSIZE #"(\d+)(?:\.(\d+))?([kKmMgGtT]?)[bB]?")

(def BSIZES
  {"k" 1024, "K" 1024,
   "m" 1048576, "M" 1048576,
   "g" 1073741824, "G" 1073741824,
   "t" 1099511627776, "T" 1099511627776})

(defn size->bytes [s]
  (when s
    (when-let [[_ n x m] (re-matches RE-BSIZE s)]
      (* (Long/parseLong n) (BSIZES m 1)))))

(defn spy [s x]
  (println s x)
  x)

(defn ssha512
  ([pwd]
   (ssha512 (rand-int 4096) pwd))
  ([salt pwd]
   (let [md (MessageDigest/getInstance "SHA-512")
         s (str (format "%04x" salt) pwd)]
     (str "SSHA512:" (b64enc (.digest md (.getBytes s)))))))

(defn ssha512-verify [pwh pwd]
  (not (empty? (for [i (range 4096) :when (= pwh (ssha512 i pwd))] true))))

