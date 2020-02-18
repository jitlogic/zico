(ns zico.web
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
    [ring.middleware.ssl :refer [wrap-ssl-redirect wrap-hsts wrap-forwarded-scheme]]
    [ring.middleware.x-headers :refer [wrap-xss-protection wrap-frame-options wrap-content-type-options]]
    [ring.util.http-response :as rhr]
    [ring.util.http-status]
    [ring.util.request :refer [body-string]]
    [ring.util.response :refer [redirect]]
    [schema.core :as s]
    [zico.schema.tdb]
    [zico.schema.server]
    [zico.trace :as ztrc]
    [zico.elastic :as zela]
    [zico.util :as zu]
    [zico.metrics :as zmet])
  (:import (com.jitlogic.zorka.common.util Base64 ZorkaUtil)))


(defn render-loading-page [_]
  {:status  200,
   :headers {"Content-Type", "text/html;charset=utf-8"}
   :body    (zu/render-page
              [:div#app
               [:div.splash-centered
                [:div.splash-frame "Loading application ..."]]]
              (include-js "/js/app.js"))})


(defn zico-error-handler [_ {:keys [reason status] :as data} _]
  (cond
    (string? data) {:status 500, :headers {}, :body {:reason data}}
    :else
    {:status  (or status 500) :headers {} :body    {:reason reason}}))


(defn wrap-cache [f]
  (fn [{:keys [uri] :as req}]
    (let [resp (f req)]
      (if (and (not zu/DEV-MODE) (or (.endsWith uri ".css") (.endsWith uri ".svg")))
        (assoc-in resp [:headers "Cache-Control"] "max-age=3600")
        (-> resp
            (assoc-in [:headers "Cache-Control"] "no-cache,no-store,max-age=0,must-revalidate")
            (assoc-in [:headers "Pragma"] "no-cache"))))))


(defn trace-detail [app-state tid sid]
  (if-let [rslt (ztrc/trace-detail app-state tid sid)]
    (rhr/ok rslt)
    (rhr/not-found {:reason "trace not found"})))

(defn attr-vals [app-state id]
  ((:attr-vals @(:tstore-state app-state)) app-state id))

(defn zico-api-routes [{{:keys [user-search user-dtrace user-detail user-tstats]} :metrics :as app-state}]
  (ca/api
    {:exceptions
     {:handlers {:zico zico-error-handler}}
     :swagger
     {:ui   "/docs"
      :spec "/swagger.json"
      :data {:basePath "/api"
             :info     {:version "1.90.6", :title "ZICO", :description "ZICO 2.x Collector API"
                        :contact {:name "Zorka.io project", :url "http://zorka.io"}}
             :tags     [{:name "config", :description "configuration objects"}
                        {:name "trace", :description "trace data search & browsing"}]
             :consumes ["application/json", "application/edn"]
             :produces ["application/json", "application/edn", "text/plain"]}}}

    (ca/context "/trace" []
      :tags ["trace"]
      (ca/POST "/" []
        :summary "search traces according to posted query"
        :body [query zico.schema.tdb/TraceSearchQuery]
        :return [zico.schema.tdb/ChunkMetadata]
        (rhr/ok (zmet/with-timer user-search (ztrc/trace-search app-state query))))
      (ca/GET "/attr/:id" []
        :summary "return all values of given attribute"
        :query-params [{limit :- s/Int 100}]
        :path-params [id :- s/Str]
        :return [s/Str]
        (rhr/ok (attr-vals app-state id)))
      (ca/GET "/:tid" []
        :summary "return all spans of a distributed trace"
        :path-params [tid :- s/Str]
        :return zico.schema.tdb/ChunkMetadata
        (rhr/ok
          (zmet/with-timer user-dtrace
            (ztrc/chunks->tree
              (ztrc/trace-search app-state {:traceid tid, :spans-only true, :limit 1024})))))
      (ca/GET "/:tid/:sid" []
        :summary "return trace execution tree"
        :path-params [tid :- s/Str, sid :- s/Str]
        :return zico.schema.tdb/TraceRecord
        (zmet/with-timer user-detail
          (trace-detail app-state tid sid)))
      (ca/GET "/:tid/:sid/stats" []
        :summary "return trace method call stats"
        :path-params [tid :- s/Str, sid :- s/Str]
        :return [zico.schema.tdb/TraceStats]
        (rhr/ok
          (zmet/with-timer user-tstats
            (ztrc/trace-stats app-state tid sid)))))

    (ca/context "/config" []
      :tags ["config"]
      (ca/GET "/filters" []
        :summary "returns configured trace filter definitions"
        :return [zico.schema.server/FilterDef]
        (rhr/ok (-> app-state :conf :filter-defs)))
      (ca/GET "/ttypes" []
        :summary "returns configured trace types"
        :return [(dissoc zico.schema.server/TraceType :render)]
        (rhr/ok (for [tt (vals (-> app-state :conf :trace-types))] (dissoc tt :when :render)))))
    (ca/context "/admin" []
      :tags ["admin"]
      (ca/GET "/attrs/:tsnum" []
        :summary "lists all symbols in given index (elastic only)"
        :path-params [tsnum :- s/Num]
        :return [s/Str],
        (rhr/ok (zela/attr-names app-state tsnum)))
      (ca/POST "/rotate" []
        :summary "rotates trace index (useful only in elastic mode)"
        :return s/Str
        (rhr/ok (zela/rotate-index app-state))))))


(defn zico-agent-routes [{{:keys [agent-agd agent-trc]} :metrics :as app-state}]
  (cc/routes
    (cc/POST "/submit/agd" {:keys [headers body]}
      (zmet/with-timer agent-agd
        (ztrc/submit-agd
          app-state
          (get headers "x-zorka-session-id")
          (.equalsIgnoreCase "true" (get headers "x-zorka-session-reset" "false"))
          (ZorkaUtil/slurp body))))
    (cc/POST "/submit/trc" {:keys [headers body]}
      (zmet/with-timer agent-trc
        (ztrc/submit-trc
          app-state
          (get headers "x-zorka-session-id")
          (get headers "x-zorka-trace-id")
          0
          (ZorkaUtil/slurp body))))))


(defn zorka-web-routes [app-state]
  (let [api-routes (zico-api-routes app-state)
        agent-routes (zico-agent-routes app-state)]
    (cc/routes
      (cc/GET "/" []
        {:status  302, :body "Redirecting...", :headers {"Location" "/view#mon/trace/list"}})

      (cc/context "/api" [] api-routes)
      (cc/context "/agent" [] agent-routes)
      (cc/context "/view" [] render-loading-page)

      (cc/GET "/metrics" []
        (zmet/prometheus-scrape app-state))

      (resources "/")
      (not-found "Not Found!\n"))))

(def RE-AUTH-HDR #"\s*(\w+)\s+(\S+)\s*")
(def RE-AUTH-VAL #"(\w+):(\S+)")

(def WWW-AUTHZ {:status 401, :body "Authentication required.",
                :headers {"WWW-Authenticate", "Basic realm=\"ZICO\", charset=\"UTF-8\""}})

(def WWW-AUTHN {:status 403, :body "Forbidden."})

(defn http-basic-filter [f users admin-users pwcheck]
  (fn [{:keys [headers uri] :as req}]
    (let [auth (get headers "authorization" "")
          [_ auths authv] (re-matches RE-AUTH-HDR auth)]
      (cond
        (.startsWith uri "/agent") (f req)
        (.startsWith uri "/metrics") (f req)
        (empty? auth) WWW-AUTHZ
        (not (.equalsIgnoreCase "Basic" auths)) WWW-AUTHZ
        :else
        (let [[_ login passwd] (re-matches RE-AUTH-VAL (String. (Base64/decode authv)))]
          (cond
            (empty? login) WWW-AUTHZ
            (empty? passwd) WWW-AUTHZ
            (nil? (get users login)) WWW-AUTHZ
            (not (pwcheck (get users login) passwd)) WWW-AUTHZ
            (not (.startsWith uri "/api/admin")) (f req)
            (contains? admin-users login) (f req)
            :else WWW-AUTHN)))
      )))

(defn password-check [pwhash passwd]
  (cond
    (.startsWith pwhash "SSHA512:") (zu/ssha512-verify pwhash passwd)
    :else (= pwhash passwd)))


(defn wrap-web-middleware [handler]
  (-> handler
      wrap-keyword-params
      wrap-multipart-params
      wrap-params
      wrap-gzip
      wrap-cookies
      wrap-content-type
      (wrap-default-charset "utf-8")
      (wrap-xss-protection {:mode :block})
      (wrap-frame-options :sameorigin)
      (wrap-content-type-options :nosniff)
      wrap-forwarded-scheme
      wrap-forwarded-remote-addr
      wrap-cache))


(defn with-zorka-web-handler [{{{:keys [type users admin-users]} :auth} :conf :as app-state}]
  (let [main-handler (-> app-state zorka-web-routes wrap-web-middleware)]
    (assoc app-state
      :main-handler
      (case type
        :http-basic (http-basic-filter main-handler users admin-users password-check)
        main-handler))))

