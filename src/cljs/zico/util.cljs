(ns zico.util
  (:require
    [cljs.pprint :as pp]))


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


(defn from-mouse-event [e]
  (let [c (.-target e), r (.getBoundingClientRect c)]
    {:x (.-clientX e), :y (.-clientY e),
     :w (.-offsetWidth c), :h (.-offsetHeight c),
     :l (.-left r), :r (.-right r), :t (.-top r), :b (.-bottom r)}))


(defn dom-attr-lookup [node attr class]
  (let [n (.-nodeName node), c (.getAttribute node "class"), v (.getAttribute node attr), p (.-parentNode node)]
    (cond
      (and (some? v) (or (nil? class) (= c class))) v
      (or (nil? p) (= "HTML" n)) nil
      :else (recur p attr class))))


(defn group [& [coll & _ :as colls]]
  (when-not (empty? coll)
    (cons
      (map first colls)
      (lazy-seq
        (apply group (map rest colls))))))


(defn merge-in [coll ks col1]
  (assoc-in coll ks (merge {} (get-in coll ks) col1)))


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

(defn ticks-to-str [ticks]
  (let [t (* ticks 65536)]
    (cond
      (= t 0) "0ms"
      (< t 1000000) (pp/cl-format nil "~dus" (int (/ t 1000)))
      (< t 100000000) (pp/cl-format nil "~4fms" (/ t 1000000))
      (< t 1000000000) (pp/cl-format nil "~dms" (int (/ t 1000000)))
      (< t 1000000000000) (pp/cl-format nil "~ds" (int (/ t 1000000000))))))

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