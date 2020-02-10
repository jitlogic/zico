(ns zico.schema.server
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
  {:path s/Str
   :dump s/Bool
   :dump-path s/Str
   :mode s/Keyword
   :max-history s/Int
   :max-size s/Int
   :current-fname s/Str
   :history-fname s/Str
   :console-pattern s/Str
   :file-pattern s/Str
   :file-level s/Keyword
   :console-level s/Keyword
   :log-levels {s/Keyword s/Keyword}})

(s/defschema AuthConfig
  {:type s/Keyword
   :users {s/Str s/Str}})


(s/defschema TraceType
  {:component             s/Str
   :render                s/Any
   :icon                  s/Str})

(s/defschema FilterDef
  {:attr s/Str
   :description s/Str
   :icon s/Str})

(s/defschema TraceStoreConfig
  {:type                      (s/enum :memory :elastic)
   :url                       s/Str
   (s/optional-key :username) s/Str
   (s/optional-key :password) s/Str
   :name                      s/Str
   :memory-size               s/Num
   :index-size                s/Num
   :index-overcommit          s/Num
   :index-count               s/Num
   :rotation-interval         s/Num
   :post-merge-pause          s/Num
   :pre-merge-segments        s/Num
   :final-merge-segments      s/Num
   :num-shards                s/Num
   :num-replicas              s/Num})

(s/defschema ZicoConf
  {:home-dir    s/Str
   :tstore TraceStoreConfig
   :http        JettyHttpConf
   :log         LoggerConfig
   :auth        AuthConfig
   :filter-defs [FilterDef]
   :trace-types {s/Keyword TraceType}
   })
