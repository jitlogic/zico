(ns zico.server.schema
  "ZICO configuration file schema and access functions."
  (:require
    [schema.core :as s]
    [zico.backend.schema :as zbsc]))


(s/defschema TextIndexConfig
  {:base-size s/Int
   :max-size s/Int})


(s/defschema TraceStoreConfig
  {:path            s/Str
   :maint-threads   s/Int
   :maint-interval  s/Int
   :session-timeout s/Int
   :rotate          {
                     :max-size s/Int
                     :max-num  s/Int}
   :text            TextIndexConfig
   :meta            TextIndexConfig})


(s/defschema BackupConfig
  {:path s/Str
   :history s/Int})


(s/defschema AgentConfig
  {:register {:host s/Bool
              :app s/Bool
              :env s/Bool
              :attrdesc s/Bool}})


(s/defschema ZicoConf
  {:http zbsc/JettyHttpConf
   :log  zbsc/LoggerConfig
   :auth zbsc/AuthConfig
   :account zbsc/AcctConfig
   :tstore TraceStoreConfig
   :zico-db zbsc/DatabaseConfig
   :backup BackupConfig
   :agent AgentConfig
   })

