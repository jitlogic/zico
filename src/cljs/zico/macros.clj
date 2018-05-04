(ns zico.macros
  (:require
    [clojure.java.io :refer [resource]]
    [clojure.java.shell :refer [sh]]
    [clojure.data.xml :as xml])
  (:import (java.io File)))


(def RE-SVG #"([a-zA-Z0-9_\-]+)\.svg")

(def RE-VIEW-BOX #"\s*([\d\.]+)\s+([\d\.]+)\s+([\d\.]+)\s+([\d\.]+)\s*")


(defn svg-parse-view-box [s]
  (when-let [[_ x y w h] (re-matches RE-VIEW-BOX s)]
    {:x (Double/parseDouble x), :y (Double/parseDouble y),
     :w (Double/parseDouble w), :h (Double/parseDouble h)}))


(defn svg-parse-icon [f]
  (let [{:keys [tag attrs content]} (xml/parse-str (slurp f))
        attrs (select-keys attrs [:viewBox :transform])
        viewBox (:viewBox attrs "0 0 1000 1000")]
    (when (and (= :svg tag) (some? content))
      {:attrs    (assoc attrs :viewBox viewBox),
       :view-box (svg-parse-view-box viewBox)
       :content  content})))


(defn svg-parse-icon-family [fdir]
  (into {}
    (for [fname (.list fdir)
          :let [[_ name] (re-matches RE-SVG fname)] :when name,
          :let [xml (svg-parse-icon (File. ^File fdir ^String fname))] :when xml]
      {name {:xml xml}})))

(defn svg-emit-icon-family [data]
  (xml/emit-str
    (xml/element
      :svg
      {:xmlns "http://www.w3.org/2000/svg", :version "1.1"
       :style "position: absolute; width: 0; height: 0; overflow: hidden;"}
      (xml/element
        :defs {}
        (for [[n {{:keys [attrs content]} :xml}] data]
          (xml/element :symbol (merge attrs {:id n})
                       (xml/element (:tag (first content)) (:attrs (first content)))))))))


(defn svg-compiled-icon-family [fdir]
  (let [data (svg-parse-icon-family fdir), outd (File. "target/cljsbuild/public/img")]
    (.mkdirs outd)
    (spit (File. outd (str (.getName fdir) ".svg")) (svg-emit-icon-family data))
    (into {} (for [[k v] data] {k (assoc-in v [:xml :content] nil)}))))



(defmacro svg-compiled-icon-set [root & opts]
  (let [root (File. (str "resources/" root))]
    (into {}
      (for [^String fdir (.list root) :when (re-matches #"[a-z]+" fdir)]
        {fdir (svg-compiled-icon-family (File. root fdir))}))))


(defmacro zorka-version-info []
  (try
    (let [project (read-string (slurp "project.clj"))]
      (nth project 2))
    (catch Exception e (.getMessage e))))


(defmacro zorka-build-info []
  (try
    (let [{:keys [exit out]} (sh "git" "log" "-1" "--pretty='%h (%ai)'")]
      (if (= exit 0) (.replaceAll out "'" "") "<development>"))
    (catch Exception _ "<development>")))

