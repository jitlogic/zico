(ns zico.macros
  (:require
    [clojure.java.shell :refer [sh]]))

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

