(ns zico.schema.ui.forms
  (:require
    [schema.core :as s]))

(s/defschema FormDatum
  {(s/optional-key :text)  s/Str                            ; unparsed value (used in DOM structures)
   (s/optional-key :value) s/Any                            ; parsed value (used in application logic)
   })

