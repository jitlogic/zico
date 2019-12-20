(ns zico.util
  (:require
    [aero.core :as aero]
    [hiccup.page :refer [html5 include-css]]
    [schema.core :as s]
    [clj-time.coerce :as ctco]
    [clj-time.format :as ctfo]
    [clojure.tools.logging :as log])
  (:import
    (java.io File)
    (java.util Properties HashMap Base64)))


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
   (filter-unique seq #{}))
  ([[obj & seq] acc]
   (cond
     (nil? obj) nil
     (acc obj) (recur seq acc)
     :else (lazy-seq (cons obj (filter-unique seq (conj acc obj)))))))


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


(defn to-int [x]
  (cond
    (int? x) x
    (string? x) (Integer/parseInt x)
    (number? x) (.longValue x)
    :else (throw (RuntimeException. (str "Cannot coerce to int: " x)))))


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

(defn java-map [m]
  (let [rslt (HashMap.)]
    (doseq [[k v] m] (.put rslt k v))
    rslt))


(defn without-nil-vals [m]
  (into {} (for [[k v] m :when (some? v)] {k v})))

(defn b64enc [b]
  (.encodeToString (Base64/getEncoder) b))

(defn b64dec [^String s]
  (.decode (Base64/getDecoder) s))

(defn parse-hex-tid [^String s]
  "Parses trace or span ID. Returns vector of two components, if second component does not exist, 0."
  (cond
    (nil? s) nil
    (re-matches #"[0-9a-fA-F]{16}" s) [(.longValue (BigInteger. s 16)) 0]
    (re-matches #"[0-9a-fA-F]{32}" s) [(.longValue (BigInteger. (.substring s 0 16) 16))
                                       (.longValue (BigInteger. (.substring s 16 32) 16))]
    :else nil))
