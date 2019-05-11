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
    [zico.trace :as ztrc]
    [zico.util :as zu])
  (:import (com.jitlogic.netkit.util NetkitUtil)
           (io.zorka.tdb.store RotatingTraceStore)))


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


(defn trace-detail [app-state tid sid depth]
  (if-let [rslt (ztrc/trace-detail app-state depth tid sid)]
    (rhr/ok rslt)
    (rhr/not-found {:reason "trace not found"})))


(defn zico-api-routes [app-state]
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
             :produces ["application/json", "application/edn"]}}}

    (ca/context "/trace" []
      :tags ["trace"]
      (ca/POST "/" []
        :summary "search traces according to posted query"
        :body [query zico.schema.tdb/TraceSearchQuery]
        :return [zico.schema.tdb/ChunkMetadata]
        (rhr/ok (ztrc/trace-search app-state query)))
      (ca/GET "/attr/:id" []
        :summary "return all values of given attribute"
        :query-params [{limit :- s/Int 100}]
        :path-params [id :- s/Str]
        :return [s/Str]
        (rhr/ok
          (vec (sort (seq (.getAttributeValues ^RotatingTraceStore (:tstore app-state) id limit))))))
      (ca/GET "/:tid/:sid/stats" []
        :summary "return trace method call stats"
        :path-params [tid :- s/Str, sid :- s/Str]
        :return [zico.schema.tdb/TraceStats]
        (rhr/ok (ztrc/trace-stats app-state tid sid)))
      (ca/GET "/:tid/:sid" []
        :summary "return trace execution tree"
        :path-params [tid :- s/Str, sid :- s/Str]
        :query-params [depth :- s/Int]
        :return zico.schema.tdb/TraceRecord
        (trace-detail app-state tid sid (or depth 1))))

    (ca/context "/config" []
      :tags ["config"]
      (ca/GET "/filters" []
        :summary "get filter definitions"
        :return [zico.schema.server/FilterDef]
        (rhr/ok (-> app-state :conf :filter-defs)))
      (ca/GET "/ttypes" []
        :summary "trace types"
        :return [(dissoc zico.schema.server/TraceType :render)]
        (rhr/ok (for [tt (vals (-> app-state :conf :trace-types))] (dissoc tt :when :render)))))
    ))


(defn zico-agent-routes [app-state]
  (cc/routes
    (cc/POST "/submit/agd" {:keys [headers body]}
      (ztrc/submit-agd
        app-state
        (get headers "x-zorka-session-id")
        (get headers "x-zorka-session-reset")
        (NetkitUtil/toByteArray body)))
    (cc/POST "/submit/trc" {:keys [headers body]}
      (ztrc/submit-trc
        app-state
        (get headers "x-zorka-session-id")
        (get headers "x-zorka-trace-id")
        (NetkitUtil/toByteArray body)))))


(defn zorka-web-routes [app-state]
  (let [api-routes (zico-api-routes app-state)
        agent-routes (zico-agent-routes app-state)]
    (cc/routes
      (cc/GET "/" []
        {:status  302, :body "Redirecting...", :headers {"Location" "/view/mon/trace/list"}})

      (cc/context "/api" [] api-routes)
      (cc/context "/agent" [] agent-routes)
      (cc/context "/view" [] render-loading-page)

      (resources "/")
      (not-found "Not Found.\n"))))


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


(defn with-zorka-web-handler [app-state]
  (let [main-handler (-> app-state zorka-web-routes wrap-web-middleware)]
    (assoc app-state
      :web-handler (-> app-state zorka-web-routes wrap-web-middleware)
      :main-handler main-handler)))

