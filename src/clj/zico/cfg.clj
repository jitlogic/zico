(ns zico.cfg
  "ZICO configuration file schema"
  (:require
    [clojure.spec.alpha :as s]))

(defn regex? [re]
  (s/and string? #(re-matches re %)))

(def RE-URL #"https?://.*")

; HTTP server
(s/def :zcfg.http/port (s/and integer? #(and (> % 10) (< % 65535))))
(s/def :zcfg.http/tls? boolean?)
(s/def :zcfg.http/keystore string?)
(s/def :zcfg.http/keystore-pass string?)
(s/def :zcfg.http/worker-threads (s/and integer? #(and (> % 0) (< % 128))))
(s/def :zcfg.http/max-body (s/and integer? #(> % 65536)))
(s/def :zcfg.http/tls-ciphers (s/coll-of (regex? #"[A-Z0-9_]+")))
(s/def :zcfg.http/tls-protocols (s/coll-of (regex? #"[A-Za-z0-9_\.]+")))

(s/def :zcfg/http
  (s/keys :req-un [:zcfg.http/port :zcfg.http/tls? :zcfg.http/keystore :zcfg.http/keystore-pass :zcfg.http/worker-threads
                   :zcfg.http/max-body :zcfg.http/tls-ciphers :zcfg.http/tls-protocols]))

; Loggers
(s/def :zcfg.log.out/path string?)
(s/def :zcfg.log.out/backlog (s/and integer? #(and (>= % 0) (< % 1024))))
(s/def :zcfg.log.out/max-size (s/and integer? #(and (>= % 65536))))

(s/def :zcfg.log/level #{:trace :debug :info :warn :error})
(s/def :zcfg.log/main (s/keys :req-un [:zcfg.log.out/path :zcfg.log.out/backlog :zcfg.log.out/max-size]))

(s/def :zcfg/log
  (s/keys :req-un [:zcfg.log/level :zcfg.log/main]))

; Authentication settings
(s/def :zcfg.auth/auth #{:none :local :cas10 :cas20})

(defmulti auth-mode :auth)

(defmethod auth-mode :none [_]
  (s/keys :req-un [:zcfg.auth/auth]))

(defmethod auth-mode :local [_]
  (s/keys :req-un [:zcfg.auth/auth]))

(s/def :zcfg.auth/cas-url (regex? RE-URL))
(s/def :zcfg.auth/app-url (regex? RE-URL))

(defmethod auth-mode :cas10 [_]
  (s/keys :req-un [:zcfg.auth/auth :zcfg.auth/cas-url :zcfg.auth/app-url]))

(defmethod auth-mode :cas20 [_]
  (s/keys :req-un [:zcfg.auth/auth :zcfg.auth/cas-url :zcfg.auth/app-url]))

(s/def :zcfg/auth
  (s/multi-spec auth-mode :auth))


; Account creation settings
(s/def :zcfg.acct/create? boolean?)
(s/def :zcfg.acct/update? boolean?)

(s/def :zcfg.acct.a/fullname (s/or :string string? :keyword keyword?))
(s/def :zcfg.acct.a/comment (s/or :string string? :keyword keyword?))
(s/def :zcfg.acct.a/email (s/or :string string? :keyword keyword?))

(s/def :zcfg.acct/attrmap
  (s/keys :req-un [:zcfg.acct.a/fullname :zcfg.acct.a/comment :zcfg.acct.a/email]))

(s/def :zcfg.acct.r/attr keyword?)
(s/def :zcfg.acct.r/admin string?)
(s/def :zcfg.acct.r/viewer string?)

(s/def :zcfg.acct/rolemap
  (s/keys :req-un [:zcfg.acct.r/attr :zcfg.acct.r/admin :zcfg.acct.r/viewer]))

(s/def :zcfg/account
  (s/keys :req-un [:zcfg.acct/create? :zcfg.acct/update? :zcfg.acct/attrmap :zcfg.acct/rolemap]))

; Trace store settings
(s/def :zcfg.tstore/path string?)
(s/def :zcfg.tstore/maint-threads (s/and integer? #(>= % 1)))
(s/def :zcfg.tstore/maint-interval (s/and integer? #(>= % 4)))
(s/def :zcfg.tstore/session-timeout (s/and integer? #(>= % 30)))

(s/def :zcfg.tstore.r/max-size (s/and integer? #(>= % 256)))
(s/def :zcfg.tstore.r/max-num (s/and integer? #(>= % 2)))

(s/def :zcfg.tstore/rotate
  (s/keys :req-un [:zcfg.tstore.r/max-size :zcfg.tstore.r/max-num]))

(s/def :zcfg.tstore.i/base-size (s/and integer? #(>= % 1024)))
(s/def :zcfg.tstore.i/max-size (s/and integer? #(>= % 16384)))

(s/def :zcfg.tstore/text
  (s/keys :req-un [:zcfg.tstore.i/base-size :zcfg.tstore.i/max-size]))

(s/def :zcfg.tstore/meta
  (s/keys :req-un [:zcfg.tstore.i/base-size :zcfg.tstore.i/max-size]))

(s/def :zcfg/tstore
  (s/keys :req-un [:zcfg.tstore/path :zcfg.tstore/maint-threads :zcfg.tstore/maint-interval
                   :zcfg.tstore/rotate :zcfg.tstore/text :zcfg.tstore/meta]))


; Database configuration
(s/def :zcfg.db/subprotocol #{"h2" "mysql"})
(s/def :zcfg.db/classname #{"org.h2.Driver" "com.mysql.jdbc.Driver"})
(s/def :zcfg.db/subname string?)
(s/def :zcfg.db/user string?)
(s/def :zcfg.db/password string?)
(s/def :zcfg.db/host string?)
(s/def :zcfg.db/port (s/and integer? #(and (>= % 20) (<= % 65535))))
(s/def :zcfg.db/mysqldump string?)
(s/def :zcfg.db/mysql string?)
(s/def :zcfg.db/init-source #{:internal :external :all})
(s/def :zcfg.db/init-mode #{:append :overwrite :skip})

(s/def :zcfg/zico-db
  (s/keys :req-un [:zcfg.db/subname :zcfg.db/classname :zcfg.db/subname :zcfg.db/user :zcfg.db/password :zcfg.db/host
                   :zcfg.db/port :zcfg.db/mysqldump :zcfg.db/mysql :zcfg.db/init-mode :zcfg.db/init-source]))

(s/def :zcfg.bkp/path string?)
(s/def :zcfg.bkp/history (s/and integer? #(>= % 1)))

(s/def :zcfg/backup
  (s/keys :req-un [:zcfg.bkp/path :zcfg.bkp/history]))

(s/def :zcfg.agt.r/host boolean?)
(s/def :zcfg.agt.r/app boolean?)
(s/def :zcfg.agt.r/env boolean?)
(s/def :zcfg.agt.r/attrdesc boolean?)

(s/def :zcfg.agt/register
  (s/keys :req-un [:zcfg.agt.r/host :zcfg.agt.r/app :zcfg.agt.r/env :zcfg.agt.r/attrdesc]))

(s/def :zcfg/agent
  (s/keys :req-un [:zcfg.agt/register]))


; Main configuration
(s/def :zico.cfg/config
  (s/keys :req-un [:zcfg/http :zcfg/log :zcfg/auth :zcfg/account :zcfg/tstore :zcfg/zico-db :zcfg/backup :zcfg/agent]))

