(ns zico.schema.db
  (:require
    [schema.core :as s]))


(s/defschema CfgObj
  {:id s/Int
   :name s/Str
   (s/optional-key :class) s/Keyword
   (s/optional-key :comment) s/Str
   (s/optional-key :flags) s/Int})

(s/defschema CfgObjWithGlyph
  (merge
    CfgObj
    {(s/optional-key :glyph) s/Str}))

(s/defschema TType
  (merge
    CfgObjWithGlyph
    {:dmin s/Int
     :descr s/Str}))

(s/defschema App CfgObjWithGlyph)
(s/defschema Env CfgObjWithGlyph)
(s/defschema AttrDesc CfgObjWithGlyph)

(s/defschema Host
  (merge
    CfgObj
    {:authkey s/Str
     :env s/Int
     :app s/Int}))

(s/defschema HostAttr
  {:uuid s/Str
   :hostid s/Int
   :attrid s/Int
   :attrval s/Str
   (s/optional-key :flags) s/Int})

(s/defschema HostReg
  (merge
    CfgObj
    {:regkey s/Str
     (s/optional-key :env) s/Int
     (s/optional-key :app) s/Int}))

(s/defschema User
  (merge
    CfgObj
    {(s/optional-key :fullname) s/Str
     (s/optional-key :email) s/Str
     (s/optional-key :password) s/Str
     (s/optional-key :roles) #{s/Keyword}}
    ))
