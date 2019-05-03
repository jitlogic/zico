(ns zico.server.web
  (:require
    [compojure.core :as cc]
    [compojure.route :refer [not-found resources]]
    [compojure.api.sweet :as ca]
    [hiccup.page :refer [include-js include-css html5]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.middleware.default-charset :refer [wrap-default-charset]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.proxy-headers :refer [wrap-forwarded-remote-addr]]
    [ring.middleware.session :refer [wrap-session]]
    [ring.middleware.ssl :refer [wrap-ssl-redirect wrap-hsts wrap-forwarded-scheme]]
    [ring.middleware.x-headers :refer [wrap-xss-protection wrap-frame-options wrap-content-type-options]]
    [ring.util.http-response :as rhr]
    [ring.util.http-status]
    [ring.util.request :refer [body-string]]
    [ring.util.response :refer [redirect]]
    [schema.core :as s]
    [slingshot.slingshot :refer [throw+]]
    [zico.backend.admin :as zadm]
    [zico.backend.auth :as zaut :refer [wrap-zico-auth wrap-allow-roles]]
    [zico.backend.web :as zbw]
    [zico.schema.api]
    [zico.schema.db]
    [zico.schema.tdb]
    [zico.server.trace :as ztrc])
  (:import (com.jitlogic.netkit.util NetkitUtil)))


(defn trace-detail [app-state tid sid depth]
  (if-let [rslt (ztrc/trace-detail app-state depth tid sid)]
    (rhr/ok rslt)
    (rhr/not-found {:reason "trace not found"})))


(defn zico-api-routes [{:keys [obj-store] :as app-state}]
  (ca/api
    {:exceptions
     {:handlers {:zico zbw/zico-error-handler}}
     :swagger
     {:ui   "/docs"
      :spec "/swagger.json"
      :data {:basePath "/api"
             :info     {:version "1.90.6", :title "ZICO", :description "ZICO 2.x Collector API"
                        :contact {:name "Zorka.io project", :url "http://zorka.io"}}
             :tags     [{:name "cfg", :description "configuration objects"}
                        {:name "system", :description "system information"}
                        {:name "trace", :description "trace data search & browsing"}
                        {:name "user", :description "current user information"}
                        {:name "test", :description "testing API"}]
             :consumes ["application/json", "application/edn"]
             :produces ["application/json", "application/edn"]}}}

    (zbw/zico-db-resource obj-store :app zico.schema.db/App)
    (zbw/zico-db-resource obj-store :env zico.schema.db/Env)
    (zbw/zico-db-resource obj-store :host zico.schema.db/Host)
    (zbw/zico-db-resource obj-store :ttype zico.schema.db/TType)
    (zbw/zico-db-resource obj-store :hostreg zico.schema.db/HostReg)
    (zbw/zico-db-resource obj-store :user zico.schema.db/User)

    (ca/context "/system" []
      :tags ["system"]
      (ca/GET "/info" []
        :summary "system information"
        :return zico.schema.api/SystemInfo
        (rhr/ok (zadm/system-info app-state))))

    (ca/context "/user" []
      :tags ["user"]
      (ca/GET "/info" req
        :summary "current user info"
        :return zico.schema.db/User
        (rhr/ok (:user (:session req) zaut/ANON-USER)))
      (ca/POST "/password" {{:keys [user]} :session}
        :summary "change user password"
        :body [body zico.schema.api/PasswordChange]
        (zaut/change-password app-state user body)))

    (ca/context "/trace" []
      :tags ["trace"]
      (ca/POST "/" []
        :summary "search traces according to posted query"
        :body [query zico.schema.tdb/TraceSearchQuery]
        :return [zico.schema.tdb/TraceSearchRecord]
        (rhr/ok (ztrc/trace-search app-state query)))
      (ca/GET "/:tid/:sid" []
        :summary "return trace execution tree"
        :path-params [tid :- s/Str, sid :- s/Str]
        :query-params [depth :- s/Int]
        :return zico.schema.tdb/TraceRecord
        (trace-detail app-state (or depth 1) tid sid))
      (ca/GET "/:id/stats" []
        :summary "return trace method call stats"
        :path-params [tid :- s/Str, sid :- s/Str]
        :return [zico.schema.tdb/TraceStats]
        (rhr/ok (ztrc/trace-stats app-state tid sid))))
    ))


(defn zico-agent-routes [app-state]
  (cc/routes
    (cc/POST "/submit/agd" {:keys [headers body]}
      (ztrc/submit-agd
        app-state
        (get headers "x-zorka-session-id")
        (get headers "x-zorka-session-renew")
        (NetkitUtil/toByteArray body)))
    (cc/POST "/submit/trc" {:keys [headers body]}
      (ztrc/submit-trc
        app-state
        (get headers "x-zorka-session-id")
        (get headers "x-zorka-trace-id")
        (NetkitUtil/toByteArray body)))))


(defn zorka-web-routes [{{{:keys [auth]} :auth} :conf :as app-state}]
  (let [api-routes (zico-api-routes app-state)
        agent-routes (zico-agent-routes app-state)]
    (cc/routes
      (cc/GET "/" []
        {:status  302, :body "Redirecting...", :headers {"Location" "/view/mon/trace/list"}})

      (cc/context "/api" []
        (wrap-zico-auth api-routes auth))

      (cc/context "/agent" [] agent-routes)

      (cc/context "/view" []
        (wrap-zico-auth zbw/render-loading-page auth))

      (cc/GET "/login" req (zaut/handle-login app-state req))
      (cc/POST "/login" req (zaut/handle-login app-state req))
      (cc/GET "/logout" req (zaut/handle-logout app-state req))

      (resources "/")
      (not-found "Not Found.\n"))))


(defn wrap-web-middleware [handler {:keys [session-store]}]
  (-> handler
      wrap-keyword-params
      wrap-multipart-params
      wrap-params
      wrap-gzip
      (wrap-session {:store session-store})
      wrap-cookies
      wrap-content-type
      (wrap-default-charset "utf-8")
      (wrap-xss-protection {:mode :block})
      (wrap-frame-options :sameorigin)
      (wrap-content-type-options :nosniff)
      wrap-forwarded-scheme
      wrap-forwarded-remote-addr
      zbw/wrap-cache))


(defn with-zorka-web-handler [app-state]
  (let [main-handler (-> app-state zorka-web-routes (wrap-web-middleware app-state))]
    (assoc app-state
      :web-handler (-> app-state zorka-web-routes (wrap-web-middleware app-state))
      :main-handler main-handler)))

