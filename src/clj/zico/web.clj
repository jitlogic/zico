(ns zico.web
  (:require
    [clojure.data.json :as json]
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
    [taoensso.timbre :as log]
    [zico.admin :as zadm]
    [zico.auth :as zaut :refer [wrap-zico-auth wrap-allow-roles]]
    [zico.objstore :as zobj]
    [zico.schema.db]
    [zico.schema.api]
    [zico.schema.tdb]
    [zico.trace :as ztrc]
    [zico.util :as zutl])
  (:import (com.jitlogic.netkit.util NetkitUtil)))

(def DEV-MODE (.equalsIgnoreCase "true" (System/getProperty "zico.dev.mode")))

(defn render-loading-page [_]
  {:status  200,
   :headers {"Content-Type", "text/html;charset=utf-8"}
   :body    (zutl/render-page
              [:div#app
               [:div.splash-centered
                [:div.splash-frame "Loading application ..."]]]
              (include-js "/js/app.js"))})


(defn zico-cfg-resource-0 [obj-store class schema]
  (ca/resource
    {:get  {:summary "list objects"
            :responses {200 {:schema [schema]}}
            :handler
            (fn [_]
              (rhr/ok
                (for [r (zobj/find-and-get obj-store {:class class})]
                  (dissoc r :class))))}
     :post {:summary "add object"
            :middleware [[wrap-allow-roles #{:admin}]]
            :responses {201 {:schema schema :description "created object"}}
            :parameters {:body-params (dissoc schema :id)}
            :handler
            (fn [{obj :body-params}]
              (rhr/ok
                (dissoc (zobj/put-obj obj-store (assoc obj :class class)) :class)))}}))


(defn zico-cfg-resource-1 [obj-store class schema]
  (ca/resource
    {:get    {:summary "get object"
              :responses {200 {:schema schema}}
              :parameters {:path-params {:id s/Int}}
              :handler
              (fn [{{:keys [id]} :path-params}]
                (if-let [obj (zobj/get-obj obj-store {:class class, :id id})]
                  (rhr/ok (dissoc obj :class))
                  (rhr/not-found {:reason "no such object"})))}
     :put    {:summary "update object"
              :middleware [[wrap-allow-roles #{:admin}]]
              :responses {200 {:schema schema}}
              :parameters {:path-params {:id s/Int}, :body-params schema}
              :handler
              (fn [{{:keys [id]} :path-params, v :body-params}]
                (if (zobj/get-obj obj-store {:class class, :id id})
                  (rhr/ok (dissoc (zobj/put-obj obj-store (assoc v :id id, :class class)) :class))
                  (rhr/not-found {:reason "no such object"})))}
     :delete {:summary "delete object"
              :middleware [[wrap-allow-roles #{:admin}]]
              :parameters {:path-params {:id s/Int}}
              :handler
              (fn [{{:keys [id]} :path-params}]
                (do
                  (zobj/del-obj obj-store {:class class, :id id})
                  (rhr/no-content)))}}))


(defmacro zico-cfg-resource [obj-store class schema]
  (let [uri (str "/cfg/" (name class))]
    `(ca/context ~uri []
       :tags ["cfg"]
       (ca/context "/" [] (zico-cfg-resource-0 ~obj-store ~class ~schema))
       (ca/context "/:id" [] (zico-cfg-resource-1 ~obj-store ~class ~schema)))))


(defn trace-detail [app-state id depth]
  (if-let [rslt (if (.contains id "_")
               (ztrc/trace-detail-tid app-state depth false id)
               (ztrc/trace-detail app-state depth id))]
    (rhr/ok rslt)
    (rhr/not-found {:reason "trace not found"})))


(defn zico-error-handler [_ {:keys [reason status] :as data} _]
  (cond
    (string? data) {:status 500, :headers {}, :body {:reason data}}
    :else
    {:status  (or status 500) :headers {} :body    {:reason reason}}))


(defn zico-api-routes [{:keys [obj-store] :as app-state}]
  (ca/api
    {:exceptions
     {:handlers {:zico zico-error-handler}}
     :swagger
     {:ui   "/docs"
      :spec "/swagger.json"
      :data {:basePath "/api"
             :info     {:version "1.90.6", :title "ZICO", :description "ZICO 2.x Collector API"
                        :contact {:name "Zorka.io project", :url "http://zorka.io"}}
             :tags     [{:name "admin", :description "admin & management"}
                        {:name "cfg", :description "configuration objects"}
                        {:name "system", :description "system information"}
                        {:name "trace", :description "trace data search & browsing"}
                        {:name "user", :description "current user information"}
                        {:name "test", :description "testing API"}]
             :consumes ["application/json", "application/edn"]
             :produces ["application/json", "application/edn"]}}}

    (zico-cfg-resource obj-store :app zico.schema.db/App)
    (zico-cfg-resource obj-store :env zico.schema.db/Env)
    (zico-cfg-resource obj-store :host zico.schema.db/Host)
    (zico-cfg-resource obj-store :ttype zico.schema.db/TType)
    (zico-cfg-resource obj-store :hostreg zico.schema.db/HostReg)
    (zico-cfg-resource obj-store :user zico.schema.db/User)

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
      (ca/GET "/:id" []
        :summary "return trace execution tree"
        :path-params [id :- s/Str]
        :query-params [depth :- s/Int]
        :return zico.schema.tdb/TraceRecord
        (trace-detail app-state id (or depth 1)))
      (ca/GET "/:id/stats" []
        :summary "return trace method call stats"
        :path-params [id :- s/Str]
        :return [zico.schema.tdb/TraceStats]
        (rhr/ok (ztrc/trace-stats app-state id))))

    (ca/context "/admin" []
      :tags ["admin"]
      (ca/GET "/backup" []
        :allow-roles #{:admin}
        :summary "lists all backups"
        :return [zico.schema.api/BackupItem]
        (rhr/ok (zadm/backup-list app-state nil)))
      (ca/POST "/backup" []
        :allow-roles #{:admin}
        :summary "performs backup of configuration database"
        :return zico.schema.api/BackupItem
        (rhr/ok (zadm/backup app-state nil)))
      (ca/POST "/backup/:id" []
        :allow-roles #{:admin}
        :summary "restores given backup"
        :path-params [id :- s/Int]
        (rhr/ok (zadm/restore app-state id))))))


(defn json-resp [status body]
  {:status  status,
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str body)})


(defn handle-json-req [f app-state schema req]
  (let [body (body-string req)]
    (try
      (let [args (json/read-str body :key-fn keyword)]
        (if-let [chk (s/check schema args)]
          (do
            (log/error "Invalid request" (:uri req) ": " (pr-str args) ": " (pr-str chk))
            (json-resp 400 {:reason "invalid request"}))
          (let [{:keys [status body] :as r} (f app-state args)]
            (when (>= status 400)
              (log/error "Error executing " (:uri req) ": " (pr-str args) ": " status ":" body))
            (json-resp status body))))
      (catch Exception e
        (log/error e "Server error:" body)
        (json-resp 500 {:reason "malformed request or server error"})))))


(defn zico-agent-routes [app-state]
  (cc/routes
    (cc/POST "/register" req
      (handle-json-req ztrc/agent-register app-state zico.schema.api/AgentRegReq req))
    (cc/POST "/session" req
      (handle-json-req ztrc/agent-session app-state zico.schema.api/AgentSessionReq req))
    (cc/POST "/submit/agd" {:keys [headers body]}
      (ztrc/submit-agd
        app-state
        (get headers "x-zorka-agent-id")
        (get headers "x-zorka-session-uuid")
        (NetkitUtil/toByteArray body)))
    (cc/POST "/submit/trc" {:keys [headers body]}
      (ztrc/submit-trc
        app-state
        (get headers "x-zorka-agent-id")
        (get headers "x-zorka-session-uuid")
        (get headers "x-zorka-trace-uuid")
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
        (wrap-zico-auth render-loading-page auth))

      (cc/GET "/login" req (zaut/handle-login app-state req))
      (cc/POST "/login" req (zaut/handle-login app-state req))
      (cc/GET "/logout" req (zaut/handle-logout app-state req))

      (resources "/")
      (not-found "Not Found.\n"))))


(defn wrap-cache [f]
  (fn [{:keys [uri] :as req}]
    (let [resp (f req)]
      (if (and (not DEV-MODE) (or (.endsWith uri ".css") (.endsWith uri ".svg")))
        (assoc-in resp [:headers "Cache-Control"] "max-age=3600")
        (-> resp
            (assoc-in [:headers "Cache-Control"] "no-cache,no-store,max-age=0,must-revalidate")
            (assoc-in [:headers "Pragma"] "no-cache"))))))


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
      ;wrap-hsts
      ;wrap-ssl-redirect
      wrap-forwarded-scheme
      wrap-forwarded-remote-addr
      wrap-cache
      ))


(defn with-zorka-web-handler [app-state]
  (let [main-handler (-> app-state zorka-web-routes (wrap-web-middleware app-state))]
    (assoc app-state
      :web-handler (-> app-state zorka-web-routes (wrap-web-middleware app-state))
      :main-handler main-handler)))

