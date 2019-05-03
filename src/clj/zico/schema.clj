(ns zico.schema
  "ZICO configuration file schema and access functions."
  (:require [schema.core :as s]))

(s/defschema JettyHttpConf
  {:host                               s/Str
   :http?                              s/Bool
   :port                               s/Int
   (s/optional-key :ssl?)              s/Bool
   (s/optional-key :ssl-port)          s/Int
   (s/optional-key :exclude-ciphers)   [s/Str]
   (s/optional-key :exclude-protocols) [s/Str]
   :join?                              s/Bool
   :daemon?                            s/Bool
   (s/optional-key :keystore)          s/Str
   (s/optional-key :key-password)      s/Str
   (s/optional-key :truststore)        s/Str
   (s/optional-key :trust-password)    s/Str
   :min-threads                        s/Int
   :max-threads                        s/Int
   :max-queued-requests                s/Int
   (s/optional-key :client-auth)       s/Keyword
   :max-form-size                      s/Int})

(s/defschema LoggerConfig
  {:level s/Keyword                                         ; #{:trace :debug :info :warn :error}
   :main  {:path     s/Str
           :backlog  s/Int
           :max-size s/Int}})

(s/defschema TextIndexConfig
  {:base-size s/Int, :max-size s/Int})

(s/defschema TraceStoreConfig
  {:path            s/Str
   :maint-threads   s/Int
   :maint-interval  s/Int
   :session-timeout s/Int
   :rotate          {:max-size s/Int, :max-num  s/Int}
   :text            TextIndexConfig
   :meta            TextIndexConfig})

(s/defschema ZicoConf
  {:http JettyHttpConf
   :log  LoggerConfig
   :tstore TraceStoreConfig})
