(ns zico.schema
  (:require
    [schema.core :as s :include-macros true]))

(def RE-UUID #"(\p{XDigit}{8})-(\p{XDigit}{4})-(\p{XDigit}{4})-(\p{XDigit}{4})-(\p{XDigit}{12})")

(def UuidStr
  (s/pred #(re-matches RE-UUID %)))

