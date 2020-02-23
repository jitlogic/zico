(ns zico.widgets.util
  (:require
    [cljs.pprint :as pp]
    [clojure.string :as cs]))


(defn deref?
  ([v]
   (deref? v nil))
  ([v dv]
   (cond
     (nil? v) dv
     (satisfies? IDeref v) @v
     :else v)))


(defn args->attrs+children
  ([[arg0 & args1 :as args]]
   (if (map? arg0)
     (args->attrs+children args1 arg0 [])
     (args->attrs+children args {} [])))
  ([[arg0 & [arg1 & args2 :as args1] :as args] attrs children]
    (cond
      (empty? args) [attrs children]
      (keyword? arg0) (recur args2 (assoc attrs arg0 arg1) children)
      :else (recur args1 attrs (conj children arg0)))))


(defn group [& [coll & _ :as colls]]
  (when-not (empty? coll)
    (cons
      (map first colls)
      (lazy-seq
        (apply group (map rest colls))))))


(defn merge-in [coll ks col1]
  (assoc-in coll ks (merge {} (get-in coll ks) col1)))

(defn recursive-merge [map1 map2]
  "Recursive merge"
  (if (and (map? map1) (map? map2))
    (merge-with recursive-merge map1 map2)
    map2))


(defn map-to-seq
  ([attrs]
   (map-to-seq 0 attrs))
  ([level attrs]
   (when attrs
     (reduce concat
       (for [[k v] (sort-by first attrs)]
         (if (map? v)
           (cons [level k nil] (map-to-seq (inc level) v))
           [[level k v]]))))))

(defn no-nulls [o]
  (cond
    (map? o) (into {} (for [[k v] o :when (some? v)] {k v}))
    (vector? o) (vec (filter some? o))
    (seq? o) (filter some? o)
    :else o))

(defn ns-to-str [t ms?]
  (cond
    (< t 0) "N/A"
    (= t 0) "0ms"
    (< t 1000000) (pp/cl-format nil "~dus" (int (/ t 1000)))
    (< t 100000000) (pp/cl-format nil "~4fms" (/ t 1000000))
    (or ms? (< t 1000000000)) (pp/cl-format nil "~dms" (int (/ t 1000000)))
    (< t 10000000000) (pp/cl-format nil "~4fs" (/ t 1000000000))
    (< t 100000000000) (pp/cl-format nil "~3fs" (/ t 1000000000))
    :else (pp/cl-format nil "~ds" (int (/ t 1000000000)))))

(defn to-string [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    (symbol? v) (name v)
    :else (str v)))

(defn ellipsis [s limit]
  (cond
    (not (string? s)) "..."
    (> (count s) limit) (str (subs s 0 limit) "...")
    :else s
    ))

(defn glyph-parse [glyph default]
  (if-let [m (re-matches #"(.+)/([^#]+)#?(.*)?" (or glyph default))]
    (rest m)
    (glyph-parse default "awe/paw#text")))


(defn url-encode [params]
  (cs/join
    "&"
    (for [[k v] params]
      (str (js/encodeURIComponent (to-string k)) "=" (js/encodeURIComponent (to-string v))))))

(defn to-tid [{:keys [traceid spanid chnum]}]
  (str traceid ":" spanid ":" chnum))

(def RE-TID #"([0-9a-fA-F]+):([0-9a-fA-F]+):([0-9]*)")

(defn parse-tid [tid]
  (when (string? tid)
    (when-let [[_ traceid spanid chnum] (re-matches RE-TID tid)]
      {:traceid traceid, :spanid spanid :chnum chnum})))

