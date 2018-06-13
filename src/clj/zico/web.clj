(ns zico.web
  (:require
    [compojure.core :refer [GET PUT POST ANY DELETE routes context]]
    [compojure.route :refer [not-found resources]]
    [hiccup.page :refer [include-js include-css html5]]
    [ring.middleware.x-headers :refer [wrap-xss-protection wrap-frame-options wrap-content-type-options]]
    [ring.middleware.session :refer [wrap-session]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.default-charset :refer [wrap-default-charset]]
    [ring.middleware.proxy-headers :refer [wrap-forwarded-remote-addr]]
    [ring.middleware.ssl :refer [wrap-ssl-redirect wrap-hsts wrap-forwarded-scheme]]
    [ring.util.request :refer [body-string]]
    [ring.util.response :refer [redirect]]
    [slingshot.slingshot :refer [try+]]
    [clojure.data.json :as json]
    [zico.auth :as zaut]
    [zico.objstore :as zobj]
    [zico.trace :as ztrc]
    [zico.util :as zutl]
    [zico.admin :as zadm]
    [taoensso.timbre :as log]))


(defn render-loading-page []
  (zutl/render-page
    [:div#app [:h3 "Loading application ..."]]
    (include-js "/js/app.js")))


(def OBJ-CLASSES (into #{} (map name (keys zobj/OBJ-TYPES))))


(defn parse-rest-params [{:keys [headers body] :as req}]
  (let [^String content-type (get headers "content-type" "application/json")]
    (try
      (cond
        (empty? body) nil
        (.startsWith content-type "application/json") (zutl/rekey-map (json/read-str body))
        (.startsWith content-type "application/edn") (clojure.edn/read-string body)
        (.startsWith content-type "application/zorka") {:format :cbor}
        :else nil
        ))))


(defn wrap-rest-request [f]
  (fn [{:keys [request-method uri] :as req}]
    (let [req (assoc req :body (body-string req))]
      (cond
        (= request-method :get) (f req)
        (and (string? uri) (not (or (.startsWith uri "/data") (.startsWith uri "/agent") (.startsWith uri "/user")))) (f req)
        (or (= request-method :post) (= request-method :put))
        (if-let [params (parse-rest-params req)]
          (f (assoc req :data params))
          {:status 405, :msg "Cannot parse REST parameters."})
        :else (f req)))))


(defn wrap-rest-response [f]
  (fn [{:keys [headers] :as req}]
    (let [{:keys [body] :as rslt} (f req)
          content-type (or (get headers "accept") (get headers "content-type") "application/json")]
      (cond
        (nil? body) rslt
        (not (map? body)) rslt
        (not= :rest (:type body)) rslt
        (.startsWith content-type "application/json")
        (assoc-in
          (assoc rslt :body (json/write-str (:data body)))
          [:headers "content-type"] "application/json")
        (.startsWith content-type "application/edn")
        (assoc-in
          (assoc rslt :body (pr-str (:data body)))
          [:headers "content-type"] "application/edn")
        :else
        (assoc-in
          (assoc rslt :body (json/write-str (:data body)))
          [:headers "content-type"] "application/json")))))


(defn zorka-agent-routes [{:keys [obj-store] :as app-state}]
  (routes
    ; Agent API
    (POST "/agent/register" req
      (ztrc/agent-register app-state req))

    (POST "/agent/session" req
      (ztrc/agent-session app-state req))

    (POST "/agent/submit/agd" {:keys [headers] :as req}
      (ztrc/submit-agd app-state
                       (get headers "x-zorka-agent-uuid")
                       (get headers "x-zorka-session-uuid")
                       (body-string req)))

    (POST "/agent/submit/trc" {:keys [headers] :as req}
      (ztrc/submit-trc app-state
                       (get headers "x-zorka-agent-uuid")
                       (get headers "x-zorka-session-uuid")
                       (get headers "x-zorka-trace-uuid")
                       (body-string req)))))


(defn zorka-web-routes [{:keys [obj-store] :as app-state}]
  (routes

    (GET "/" []
      {:status  302, :body "Redirecting...",
       :headers {"Location" "/view/mon/trace/list"}})

    ; Trace API
    (GET "/data/trace/type" _ (zobj/find-and-get obj-store :ttype))

    (POST "/data/trace/search" req (ztrc/trace-search app-state req))

    (GET "/data/trace/:uuid/detail" req
      (ztrc/trace-detail app-state 1 req))

    (GET "/data/trace/:uuid/tree" req
      (ztrc/trace-detail app-state Integer/MAX_VALUE req))

    ; Configuration data API
    (GET "/data/cfg/:class" [class]
      (if (OBJ-CLASSES class)
        (let [data (zobj/find-and-get obj-store {:class (keyword class)})]
          (zutl/rest-result data))
        (zutl/rest-error "Resource class not found." 404)))

    (POST "/data/cfg/:class" {data :data {:keys [class]} :params :as req}
      (if (OBJ-CLASSES class)
        (zobj/put-obj obj-store (assoc data :class (keyword class)))
        (zutl/rest-error "Resource class not found." 404)))

    (GET "/data/cfg/:class/:uuid" [class uuid]
      (if (OBJ-CLASSES class)
        (if-let [obj (zobj/get-obj obj-store uuid)]
          (zutl/rest-result obj))
        (zutl/rest-error "Resource class not found." 404)))

    (PUT "/data/cfg/:class/:uuid" {data :data {:keys [class uuid]} :params :as req}
      (if (OBJ-CLASSES class)
        (if-let [obj (zobj/get-obj obj-store uuid)]
          (zutl/rest-result (zobj/put-obj obj-store (merge obj data)))
          (zutl/rest-error "Resource not found." 404))))

    (DELETE "/data/cfg/:class/:uuid" {{:keys [class uuid]} :params}
      (when (OBJ-CLASSES class)
        (let [obj (zobj/get-obj obj-store uuid)]
          (if obj
            (zutl/rest-result (zobj/del-obj obj-store uuid))
            (zutl/rest-error "Object not found." 404)))))

    ; User info
    (GET "/user/info" _ (zutl/rest-result zaut/*current-user*))

    ; Changes password
    (POST "/user/password" req (zaut/change-password app-state req))

    ; Application views
    (GET "/view/:section" [_] (render-loading-page))
    (GET "/view/:section/:name" [_] (render-loading-page))
    (GET "/view/:section/:name/:cmp" [_] (render-loading-page))

    (ANY "/login" req (zaut/handle-login app-state req))
    (GET "/logout" req (zaut/handle-logout app-state req))

    (POST "/admin/backup" req (zadm/backup app-state req))
    (GET "/admin/backup" req (zadm/backup-list app-state req))
    (POST "/admin/backup/:id/restore" req (zadm/restore app-state req))

    (GET "/system/info" req (zadm/system-info app-state req))

    (resources "/")
    (not-found "Not Found.\n")))


(defn wrap-cache [f]
  (fn [req]
    (let [resp (f req)]
      (-> resp
          (assoc-in [:headers "Cache-Control"] "no-cache,no-store,max-age=0,must-revalidate")
          (assoc-in [:headers "Pragma"] "no-cache")))))

(defn debug-handler [f text]
  (fn [req]
    (println "ENTER: (" text ")" req)
    (f req)))


(defn wrap-web-middleware [handler {{auth :auth} :conf, :keys [session-store] :as app-state}]
  (let [wrap-auth (if (= :none (:auth auth)) identity zaut/wrap-user-auth)]
    (-> handler
        wrap-rest-request
        wrap-rest-response
        wrap-keyword-params
        wrap-multipart-params
        wrap-params
        wrap-auth
        zaut/wrap-keep-session
        (wrap-session {:store session-store})
        ;(without-agent-session session-store)
        wrap-cookies
        wrap-content-type
        (wrap-default-charset "utf-8")
        ;(wrap-xss-protection {:mode :block})
        ;(wrap-frame-options :sameorigin)
        ;(wrap-content-type-options :nosniff)
        ;wrap-hsts
        ;wrap-ssl-redirect
        wrap-forwarded-scheme
        wrap-forwarded-remote-addr
        wrap-cache
        )))


(defn wrap-agent-middleware [handler]
  (-> handler
      wrap-rest-request
      wrap-rest-response))


(defn web-agent-switch [web-handler agent-handler]
  (fn [{:keys [uri] :as req}]
    (println "uri=" uri)
    (if (.startsWith uri "/agent")
      (agent-handler req)
      (web-handler req))))


(defn with-zorka-web-handler [app-state]
  (let [web-handler (-> app-state zorka-web-routes (wrap-web-middleware app-state))
        agent-handler (-> app-state zorka-agent-routes wrap-agent-middleware)
        main-handler (web-agent-switch web-handler agent-handler)]
    (assoc app-state
      :agent-handler agent-handler
      :web-handler web-handler
      :main-handler main-handler)))

