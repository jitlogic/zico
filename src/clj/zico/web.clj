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
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.util.request :refer [body-string]]
    [ring.util.response :refer [redirect]]
    [clojure.data.json :as json]
    [zico.auth :as zaut]
    [zico.objstore :as zobj]
    [zico.trace :as ztrc]
    [zico.util :as zutl]
    [zico.admin :as zadm])
  (:import (com.jitlogic.netkit.util NetkitUtil)))

(def DEV-MODE (.equalsIgnoreCase "true" (System/getProperty "zico.dev.mode")))

(defn render-loading-page []
  (zutl/render-page
    [:div#app [:h3 "Loading application ..."]]
    (include-js "/js/app.js")))


(def OBJ-CLASSES (into #{} (map name (keys zobj/OBJ-TYPES))))

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

(defn parse-rest-params [headers body]
  (let [^String content-type (get headers "content-type" "application/json")
        body (body-string {:body body})]
    (try
      (cond
        (empty? body) nil
        (.startsWith content-type "application/json") (zutl/rekey-map (json/read-str body))
        (.startsWith content-type "application/edn") (clojure.edn/read-string body)
        (.startsWith content-type "application/zorka") {:format :cbor}
        :else nil
        ))))

(defn arg-prep [arg]
  (cond
    (symbol? arg) {:keys ['body 'headers 'request-method] :as 'req}
    (map? arg) (assoc arg :keys (into [] (into #{'headers 'body 'request-method} (:keys arg []))) :as 'req)
    (vector? arg) {{:keys arg} :params :keys ['body 'headers 'request-method] :as 'req}
    :else (throw (RuntimeException. (str "Illegal arg: " arg)))))

(def EMPTY-METHODS #{:get :delete})

(defmacro REST [[met uri arg & code]]
  (let [argp (arg-prep arg)]
    `(~met ~uri ~argp
       (let [data# (parse-rest-params ~'headers ~'body)]
         (cond
           (and (nil? data#) (nil? (EMPTY-METHODS ~'request-method))) {:status 405, :msg "Cannot parse REST parameters."}
           :else (let [~argp (assoc ~'req :data data#)] ~@code))))))

(defn zorka-agent-routes [app-state]
  (routes
    ; Agent API
    (REST
      (POST "/agent/register" req
        (ztrc/agent-register app-state req)))

    (REST
      (POST "/agent/session" req
        (ztrc/agent-session app-state req)))

    (POST "/agent/submit/agd" {:keys [headers body]}
      (ztrc/submit-agd app-state
                       (get headers "x-zorka-agent-uuid")
                       (get headers "x-zorka-session-uuid")
                       (NetkitUtil/toByteArray body)))

    (POST "/agent/submit/trc" {:keys [headers body]}
      (ztrc/submit-trc app-state
                       (get headers "x-zorka-agent-uuid")
                       (get headers "x-zorka-session-uuid")
                       (get headers "x-zorka-trace-uuid")
                       (NetkitUtil/toByteArray body)))))


(defn zorka-web-routes [{:keys [obj-store] :as app-state}]
  (routes

    (GET "/" []
      {:status  302, :body "Redirecting...",
       :headers {"Location" "/view/mon/trace/list"}})

    ; Trace API
    (GET "/data/trace/type" _ (zobj/find-and-get obj-store :ttype))

    (REST
      (POST "/data/trace/search" req
        (ztrc/trace-search app-state req)))

    (GET "/data/trace/:uuid/detail" req
      (let [^String uuid (-> req :params :uuid)]
        (if (.contains uuid "_")
          (ztrc/trace-detail-tid app-state 1, false uuid)
          (ztrc/trace-detail app-state 1 uuid))))

    (GET "/data/trace/:uuid/tree" req
      (let [^String uuid (-> req :params :uuid)]
        (if (.contains uuid "_")
          (ztrc/trace-detail-tid app-state Integer/MAX_VALUE, false uuid)
          (ztrc/trace-detail app-state Integer/MAX_VALUE uuid))))

    (GET "/data/trace/:uuid/stats" req
      (let [^String uuid (-> req :params :uuid)]
        (ztrc/trace-stats app-state uuid)))

    (GET "/data/cfg" _
      (zutl/rest-result
        (zobj/get-tstamps obj-store)))

    ; Configuration data API
    (GET "/data/cfg/:class" [class]
      (if (OBJ-CLASSES class)
        (let [data (zobj/find-and-get obj-store {:class (keyword class)})]
          (zutl/rest-result data))
        (zutl/rest-error "Resource class not found." 404)))

    (REST
      (POST "/data/cfg/:class" {data :data {:keys [class]} :params}
        (if (OBJ-CLASSES class)
          (zobj/put-obj obj-store (assoc data :class (keyword class)))
          (zutl/rest-error "Resource class not found." 404))))

    (GET "/data/cfg/:class/:uuid" [class uuid]
      (if (OBJ-CLASSES class)
        (if-let [obj (zobj/get-obj obj-store uuid)]
          (zutl/rest-result obj))
        (zutl/rest-error "Resource class not found." 404)))

    (REST
      (PUT "/data/cfg/:class/:uuid" {data :data {:keys [class uuid]} :params}
        (if (OBJ-CLASSES class)
          (if-let [obj (zobj/get-obj obj-store uuid)]
            (zutl/rest-result (zobj/put-obj obj-store (merge obj data)))
            (zutl/rest-error "Resource not found." 404)))))

    (REST
      (DELETE "/data/cfg/:class/:uuid" {{:keys [class uuid]} :params}
        (when (OBJ-CLASSES class)
          (let [obj (zobj/get-obj obj-store uuid)]
            (if obj
              (zutl/rest-result (zobj/del-obj obj-store uuid))
              (zutl/rest-error "Object not found." 404))))))

    ; User info
    (GET "/user/info" req
      (zutl/rest-result zaut/*current-user*))

    ; Changes password
    (REST
      (POST "/user/password" req
        (zaut/change-password app-state req)))

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
  (fn [{:keys [uri] :as req}]
    (let [resp (f req)]
      (if (and (not DEV-MODE) (or (.endsWith uri ".css") (.endsWith uri ".svg")))
        (assoc-in resp [:headers "Cache-Control"] "max-age=3600")
        (-> resp
            (assoc-in [:headers "Cache-Control"] "no-cache,no-store,max-age=0,must-revalidate")
            (assoc-in [:headers "Pragma"] "no-cache"))))))


(defn wrap-web-middleware [handler {{auth :auth} :conf, :keys [session-store] :as app-state}]
  (let [wrap-auth (if (= :none (:auth auth)) identity zaut/wrap-user-auth)]
    (-> handler
        wrap-rest-response
        wrap-keyword-params
        wrap-multipart-params
        wrap-params
        wrap-gzip
        wrap-auth
        zaut/wrap-keep-session
        (wrap-session {:store session-store})
        wrap-cookies
        wrap-content-type
        (wrap-default-charset "utf-8")
        ;(wrap-xss-protection {:mode :block})
        (wrap-frame-options :sameorigin)
        (wrap-content-type-options :nosniff)
        ;wrap-hsts
        ;wrap-ssl-redirect
        wrap-forwarded-scheme
        wrap-forwarded-remote-addr
        wrap-cache
        )))


(defn wrap-agent-middleware [handler]
  (-> handler
      wrap-rest-response))


(defn web-agent-switch [web-handler agent-handler]
  (fn [{:keys [uri] :as req}]
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

