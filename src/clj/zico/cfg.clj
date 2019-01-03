(ns zico.cfg
  "ZICO configuration file schema and access functions."
  (:require
    [aero.core]
    [schema.core :as s]))

(s/defschema JettyHttpConf
  {:host                            s/Str
   :http?                           s/Bool
   :port                            s/Int
   :ssl?                            s/Bool
   :ssl-port                        s/Int
   :exclude-ciphers                 [s/Str]
   :exclude-protocols               [s/Str]
   :join?                           s/Bool
   :daemon?                         s/Bool
   :keystore                        s/Str
   :key-password                    s/Str
   (s/optional-key :truststore)     s/Str
   (s/optional-key :trust-password) s/Str
   :min-threads                     s/Int
   :max-threads                     s/Int
   :max-queued-requests             s/Int
   (s/optional-key :client-auth)    s/Keyword
   :max-form-size                   s/Int})

(s/defschema LoggerConfig
  {:level s/Keyword                                         ; #{:trace :debug :info :warn :error}
   :main  {:path     s/Str
           :backlog  s/Int
           :max-size s/Int}})

(s/defschema AuthConfig
  {:auth s/Keyword                                          ; #{:none :local :cas10 :cas20}
   (s/optional-key :cas-url) s/Str
   (s/optional-key :app-url) s/Str})

(s/defschema AcctConfig
  {:create? s/Bool,
   :update? s/Bool,
   :attrmap {:fullname s/Str
             :comment s/Str
             :email s/Str}
   :rolemap {:attr s/Keyword
             :admin s/Str
             :viewer s/Str}})

(s/defschema TextIndex
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
   :text TextIndex
   :meta TextIndex})

(s/defschema DatabaseConfig
  {:subprotocol s/Str                                       ; #{"h2" "mysql"}
   :classname   s/Str                                       ; #{"org.h2.Driver" "com.mysql.jdbc.Driver"}
   :subname     s/Str
   :user        s/Str
   :password    s/Str
   :host        s/Str
   :port        s/Int
   :mysqldump   s/Str
   :mysql       s/Str
   :init-source s/Keyword                                   ; #{:internal :external :all}
   :init-mode   s/Keyword                                   ; #{:append :overwrite :skip}
   })

(s/defschema BackupConfig
  {:path s/Str
   :history s/Int})

(s/defschema AgentConfig
  {:register {:host s/Bool
              :app s/Bool
              :env s/Bool
              :attrdesc s/Bool}})

(s/defschema ZicoConf
  {:http JettyHttpConf
   :log  LoggerConfig
   :auth AuthConfig
   :account AcctConfig
   :tstore TraceStoreConfig
   :zico-db DatabaseConfig
   :backup BackupConfig
   :agent AgentConfig})
