(ns zico.webapi-test
  (:require
    [zico.server.main :refer [zorka-app-state]]
    [zico.test-util :refer
     [zorka-integ-fixture *root-path* time-travel zorka obj-store trace-store]]
    [clojure.test :refer :all]
    [zico.backend.objstore :as zobj]
    [zico.server.trace :as ztrc]
    [clojure.data.json :as json]))


(use-fixtures :each zorka-integ-fixture)


(defn rest-post [uri data]
  (let [r (zorka {:uri     uri, :request-method :post,
                  :scheme  "http"
                  :headers {"content-type" "application/edn"}
                  :body    (json/write-str data)})]
    (if (some? (:body r)) (assoc r :body (json/read-str (:body r) :key-fn keyword)) r)))


