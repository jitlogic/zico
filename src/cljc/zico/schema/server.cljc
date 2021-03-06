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
   (s/optional-key :keystore-type)     s/Str
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
   :users {s/Str s/Str}
   :admin-users #{s/Str}})

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
   (s/optional-key :trust-store) s/Str
   (s/optional-key :trust-store-type) s/Str
   (s/optional-key :trust-store-pass) s/Str
   (s/optional-key :keystore) s/Str
   (s/optional-key :keystore-type) s/Str
   (s/optional-key :keystore-pass) s/Str
   :instance                  s/Str
   :name                      s/Str
   :session-timeout           s/Num
   :memstore-size-max         s/Num
   :memstore-size-del         s/Num
   :index-size                s/Num
   :index-overcommit          s/Num
   :index-count               s/Num
   :writer-threads            s/Num
   :writer-queue              s/Num
   :timeout                   s/Num
   :threads                   s/Num
   (s/optional-key :default-per-route) s/Num
   (s/optional-key :insecure?) s/Bool
   :post-merge-pause          s/Num
   :pre-merge-segments        s/Num
   :final-merge-segments      s/Num
   :number_of_shards          s/Num
   :number_of_replicas        s/Num})

(s/defschema MetricsRegistryConf
  {:type (s/enum :none :simple :jmx :prometheus :elastic)
   :prefix s/Str
   :step s/Num
   :threads s/Num
   :conn-timeout s/Num
   :read-timeout s/Num
   :batch-size s/Num
   :host s/Str
   :index s/Str
   :username s/Str
   :password s/Str})



(s/defschema ZicoConf
  {:home-dir        s/Str
   :tstore          TraceStoreConfig
   :http            JettyHttpConf
   :log             LoggerConfig
   :auth            AuthConfig
   :metrics         MetricsRegistryConf
   :filter-defs     [FilterDef]
   :trace-types     {s/Keyword TraceType}})

