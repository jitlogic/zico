(ns zico.integration-tests
  (:require
    [zico.main :refer [zorka-app-state]]
    [zico.test-util :refer
     [zorka-integ-fixture *root-path* time-travel zico trace-store]]
    [clojure.test :refer :all]
    [clojure.data.json :as json])
  (:import
    (com.jitlogic.zorka.common.util Base64)
    (java.io ByteArrayInputStream)))

(use-fixtures :each zorka-integ-fixture)

(defn rest-post [uri data]
  (let [r (zico {:uri     uri, :request-method :post,
                  :scheme  "http"
                  :headers {"content-type" "application/edn"}
                  :body    (json/write-str data)})]
    (if (some? (:body r)) (assoc r :body (json/read-str (:body r) :key-fn keyword)) r)))

(defn read-trace-file [path]
  (let [s (slurp path), s (when s (.split s "\n"))]
    (is (some? s) (str "Cannot read input file: " path))
    (for [js (map json/read-str s)]
      {:headers (into {} (for [[k v] (get js "headers")] {(.toLowerCase k) (first v)}))
       :scheme  "http", :request-method :post,
       :body    (ByteArrayInputStream. (Base64/decode (get js "body")))
       :uri     (get js "uri")})))


(deftest load-some-traces-and-search
  (testing "Load some trace data"
    (doseq [req (read-trace-file "testdata/traces1.txt")
            :let [res (zico req)]]
      (is (< (:status res) 300)))))

