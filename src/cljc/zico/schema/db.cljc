(ns zico.schema.db
  (:require
    [schema.core :as s :include-macros true]))


(s/defschema CfgObj
  {:uuid s/Str
   :name s/Str
   (s/optional-key :comment) s/Str
   (s/optional-key :flags) s/Int})


(s/defschema TType
  (merge
    CfgObj
    {(s/optional-key :glyph) s/Str
     :descr s/Str
     (s/optional-key :dmin) s/Int}))


(s/defschema App
  (merge
    CfgObj
    {(s/optional-key :glyph) s/Str}))


(s/defschema Env
  (merge
    CfgObj
    {(s/optional-key :glyph) s/Str}))


(s/defschema AttrDesc
  (merge
    CfgObj
    {(s/optional-key :glyph) s/Str}))


(s/defschema Host
  (merge
    CfgObj
    {:authkey s/Str
     :env s/Str
     :app s/Str}))


(s/defschema HostAttr
  {:uuid s/Str
   :hostuuid s/Str
   :attruuid s/Str
   :attrval s/Str
   (s/optional-key :flags) s/Int})


(s/defschema HostReg
  (merge
    CfgObj
    {:regkey s/Str
     (s/optional-key :env) s/Str
     (s/optional-key :app) s/Str}))


(s/defschema User
  (merge
    CfgObj
    {(s/optional-key :fullname) s/Str
     (s/optional-key :email) s/Str
     (s/optional-key :password) s/Str}))


