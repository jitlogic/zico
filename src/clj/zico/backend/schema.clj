(ns zico.backend.schema
  (:require
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

