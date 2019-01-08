(ns zico.schema.api
  (:require
    [schema.core :as s]))

(s/defschema SystemInfo
  {:vm-version s/Str
   :vm-name s/Str
   :uptime s/Str
   :mem-used s/Int
   :mem-max s/Int
   :home-dir s/Str
   :tstamps {s/Keyword s/Int}})

(s/defschema PasswordChange
  {:oldPassword s/Str
   :newPassword s/Str
   :repeatPassword s/Str})

(s/defschema BackupItem
  {:id s/Int
   :tstamp s/Str
   :size s/Int})

(s/defschema AgentRegReq
  {(s/optional-key :id)    s/Int
   :rkey                   s/Str
   (s/optional-key :akey)  s/Str
   :name                   s/Str
   (s/optional-key :app)   s/Str
   (s/optional-key :env)   s/Str
   (s/optional-key :attrs) {s/Any s/Str}
   })

(s/defschema AgentRegResp
  {:id s/Int
   :authkey s/Str})

(s/defschema AgentSessionReq
  {:id s/Str
   :authkey s/Str})

(s/defschema AgentSessionResp
  {:session s/Str})

