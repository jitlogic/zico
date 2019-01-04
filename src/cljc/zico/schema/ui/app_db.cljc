(ns zico.schema.ui.app-db
  (:require
    [zico.schema.tdb :as tdb]
    [zico.schema.db :as db]
    [schema.core :as s :include-macros true]))

(s/defschema FormField
  {(s/optional-key :text) s/Str
   (s/optional-key :value)  s/Any})

(s/defschema Form
  {:uuid s/Str})

(s/defschema CfgView
  {:app s/Any
   :env s/Any
   :host s/Any
   :ttype s/Any
   :hostreg s/Any})


(s/defschema TraceFilter
  {:selected s/Str})


(s/defschema TraceListViewFilters
  {(s/optional-key :app) TraceFilter
   (s/optional-key :env) TraceFilter
   (s/optional-key :ttype) TraceFilter
   (s/optional-key :host) TraceFilter
   (s/optional-key :time) TraceFilter
   (s/optional-key :min-duration) TraceFilter})


(s/defschema TraceListView
  {:filter-attrs {s/Str s/Str}
   :filter TraceListViewFilters
   :sort {:dur s/Bool}
   :deep-search s/Bool
   :suppress s/Bool
   :search {:text s/Str}
   :selected s/Str})


(s/defschema TraceView
  {:list TraceListView
   :dist s/Any
   :tree s/Any
   :stats s/Any
   :history s/Any})


(s/defschema CfgData
  {:app {s/Str db/App}
   :env {s/Str db/Env}
   :host {s/Str db/Host}
   :ttype {s/Str db/TType}
   :hostreg {s/Str db/HostReg}
   :user {s/Str db/User}})


(s/defschema TraceData
  {(s/optional-key :list)  {s/Str tdb/TraceSearchRecord}
   (s/optional-key :dist)  {s/Str tdb/TraceSearchRecord}
   (s/optional-key :tree)  tdb/TraceRecord
   (s/optional-key :stats) [tdb/TraceStats]})


(s/defschema AppDb
  {:user {:info db/User}
   :system {:info {s/Keyword s/Str}}
   :view {:cfg CfgView
          :trace TraceView}
   :data {:cfg CfgData
          :trace TraceData}})

