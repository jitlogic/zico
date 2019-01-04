(ns zico.backend.web
  (:require
    [clojure.data.json :as json]
    [compojure.api.sweet :as ca]
    [hiccup.page :refer [include-js include-css html5]]
    [ring.util.http-response :as rhr]
    [ring.util.request :refer [body-string]]
    [schema.core :as s]
    [taoensso.timbre :as log]
    [zico.backend.auth :refer [wrap-zico-auth wrap-allow-roles]]
    [zico.backend.util :as zbu]
    [zico.backend.objstore :as zobj]))


(defn render-loading-page [_]
  {:status  200,
   :headers {"Content-Type", "text/html;charset=utf-8"}
   :body    (zbu/render-page
              [:div#app
               [:div.splash-centered
                [:div.splash-frame "Loading application ..."]]]
              (include-js "/js/app.js"))})


(defn zico-db-resource-0 [obj-store class schema]
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


(defn zico-db-resource-1 [obj-store class schema]
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


(defmacro zico-db-resource [obj-store class schema]
  (let [uri (str "/cfg/" (name class))]
    `(ca/context ~uri []
       :tags ["cfg"]
       (ca/context "/" [] (zico-db-resource-0 ~obj-store ~class ~schema))
       (ca/context "/:id" [] (zico-db-resource-1 ~obj-store ~class ~schema)))))


(defn zico-error-handler [_ {:keys [reason status] :as data} _]
  (cond
    (string? data) {:status 500, :headers {}, :body {:reason data}}
    :else
    {:status  (or status 500) :headers {} :body    {:reason reason}}))


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


(defn wrap-cache [f]
  (fn [{:keys [uri] :as req}]
    (let [resp (f req)]
      (if (and (not zbu/DEV-MODE) (or (.endsWith uri ".css") (.endsWith uri ".svg")))
        (assoc-in resp [:headers "Cache-Control"] "max-age=3600")
        (-> resp
            (assoc-in [:headers "Cache-Control"] "no-cache,no-store,max-age=0,must-revalidate")
            (assoc-in [:headers "Pragma"] "no-cache"))))))