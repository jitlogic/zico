(ns zico.schema
  (:require
    [schema.core :as s]))

(def RE-UUID #"(\p{XDigit}{8})-(\p{XDigit}{4})-(\p{XDigit}{4})-(\p{XDigit}{4})-(\p{XDigit}{12})")

(def UuidStr
  (s/pred #(re-matches RE-UUID %)))

(s/defschema ZicoError
  {(s/optional-key :status) s/Int
   :reason                  s/Str})
