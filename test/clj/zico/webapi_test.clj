(ns zico.webapi-test
  (:require
    [zico.web :as zweb]
    [zico.server :as zsvr :refer [zorka-app-state]]
    [zico.test-util :refer
     [zorka-integ-fixture *root-path* time-travel zorka obj-store trace-store]]
    [clojure.test :refer :all]
    [zico.objstore :as zobj]
    [zico.trace :as ztrc])
  (:import (io.zorka.tdb.store TraceStore)))


(use-fixtures :each zorka-integ-fixture)


(deftest test-wrap-rest-request
  (let [rfn (zweb/wrap-rest-request identity)]
    (testing "Check if JSON requests are parsed properly."
      (is (= {:a 1, :b "c"}
             (:data
               (rfn {:request-method :post, :body "{\"a\": 1, \"b\": \"c\"}", :uri "/data/test"
                     :headers        {"content-type" "application/json"}})))))
    (testing "Check if EDN requests are parsed properly."
      (is (= {:a 1, :b "c"}
             (:data
               (rfn {:request-method :post, :body "{:a 1, :b \"c\"}",
                     :headers {"content-type" "application/edn"}})))))))


(deftest test-wrap-rest-response
  (let [rfn (zweb/wrap-rest-response identity)]
    (testing "Check if JSON reply is generated properly."
      (is (= "{\"a\":1,\"b\":\"c\"}"
             (:body (rfn {:headers {"accept" "application/json"}
                          :body {:type :rest, :data {:a 1, :b "c"}}})))))
    (testing "Check if EDN reply is generated properly."
      (is (= "{:a 1 :b \"c\"}")
          (:body (rfn {:headers {"accept" "application/edn"}
                       :body {:type :rest, :data {:a 1, :b "c"}}}))))))


(defn rest-post [uri data]
  (let [r (zorka {:uri     uri, :request-method :post,
                  :headers {"content-type" "application/edn"}
                  :body    (pr-str data)})]
    (if (string? (:body r)) (assoc r :data (read-string (:body r))) r)))


(deftest test-agent-registration
  (testing "Register and check if record is present in object store."
    (let [rslt (rest-post "/agent/register" {:rkey "zorka", :name "test"})
          host (zobj/find-and-get-1 obj-store {:class :host, :name "test"})
          data {:uuid (:uuid host), :authkey (:authkey host)}]
      (is (some? host))
      (is (= rslt {:status 201, :headers {}, :body {:type :rest, :data data}}))
      (is (= "test" (:name host)))
      (is (= {} (ztrc/get-host-attrs zorka-app-state (:uuid host))))
      (is (= "DSC" (:name (zobj/get-obj obj-store (:app host)))))
      (is (= "TST" (:name (zobj/get-obj obj-store (:env host))))))))


(deftest test-agent-register-custom-app-env
  (testing "Register with custom application and environment."
    (let [seq-a (reduce max 0 (map zobj/extract-uuid-seq (zobj/find-obj obj-store {:class :app})))
          seq-e (reduce max 0 (map zobj/extract-uuid-seq (zobj/find-obj obj-store {:class :app})))
          rslt (zorka {:uri  "/agent/register", :request-method :post,
                      :data {:rkey "zorka", :name "test1", :app "TEST", :env "TEST"}})
          {:keys [app env] :as host} (zobj/find-and-get-1 obj-store {:class :host, :name "test1"})
          app-t (zobj/find-and-get-1 obj-store {:class :app, :name "TEST"})
          env-t (zobj/find-and-get-1 obj-store {:class :app, :name "TEST"})
          data {:uuid (:uuid host), :authkey (:authkey host)}]
      (is (some? host))
      (is (= {:status 201, :headers {}, :body {:type :rest, :data data}} rslt))
      (is (= {:class :app, :uuid app, :name "TEST", :comment "New :app",
              :flags 1, :glyph "awe/cube"}
             (zobj/get-obj obj-store app)))
      (is (= {:class :env, :uuid env, :name "TEST", :comment "New :env",
              :flags 1, :glyph "awe/cube"}
             (zobj/get-obj obj-store env)))
      (is (some? app-t) "New app TEST should be created.")
      (is (> (zobj/extract-uuid-seq (:uuid app-t)) seq-a) "App TEST should have highest seq num.")
      (is (some? env-t) "New env TEST should be created.")
      (is (> (zobj/extract-uuid-seq (:uuid env-t)) seq-e) "Env TEST should have highest seq num."))))


(deftest test-agent-register-custom-attrs
  (testing "Register with custom attributes and check if saved properly."
    (let [req {:rkey "zorka", :name "test2" :attrs {:LOCATION "PL-DC1", :ROOM "3"}},
          rslt (zorka {:uri  "/agent/register", :request-method :post, :data req}),
          host (zobj/find-and-get-1 obj-store {:class :host, :name "test2"}),
          data {:uuid (:uuid host), :authkey (:authkey host)},
          attr1 (zobj/find-and-get-1 obj-store {:class :attrdesc, :name "LOCATION"})
          attr2 (zobj/find-and-get-1 obj-store {:class :attrdesc, :name "ROOM"})]
      (is (some? host))
      (is (= rslt {:status 201, :headers {}, :body {:type :rest, :data data}}))
      (is (some? attr1))
      (is (some? attr2))
      (let [q1 {:class :hostattr, :attruuid (:uuid attr1), :hostuuid (:uuid host)}
            q2 {:class :hostattr, :attruuid (:uuid attr2), :hostuuid (:uuid host)}]
        (is (= "PL-DC1" (:attrval (zobj/find-and-get-1 obj-store q1))))
        (is (= "3" (:attrval (zobj/find-and-get-1 obj-store q2)))))
      (is (= {:LOCATION "PL-DC1", :ROOM "3"} (ztrc/get-host-attrs zorka-app-state (:uuid host)))))))


(deftest test-agent-session
  (testing "Register agent and obtain session."
    (let [rrslt (zorka {:uri  "/agent/register", :request-method :post,
                      :data {:rkey "zorka", :name "test"}})
          host (zobj/find-and-get-1 obj-store {:class :host, :name "test"})
          rdata {:uuid (:uuid host), :authkey (:authkey host)}
          srslt (zorka {:uri "/agent/session", :request-method :post,
                       :data {:uuid (-> rrslt :body :data :uuid),
                              :authkey (-> rrslt :body :data :authkey)}})
          sdata {:session (.getSession ^TraceStore trace-store (:uuid host))}]
      (is (some? host))
      (is (= rrslt {:status 201, :headers {}, :body {:type :rest, :data rdata}}))
      (is (= srslt {:status 200, :headers {}, :body {:type :rest, :data sdata}})))))


(deftest test-agent-register-update-data-attrs
  (testing "Register agent, then register again with different registration data."
    (let [rslt1 (zorka {:uri  "/agent/register", :request-method :post,
                      :data {:rkey "zorka", :name "test", :attrs {:A "1", :B "2"}}})
          host1 (zobj/find-and-get-1 obj-store {:class :host, :name "test"})
          data1 {:uuid (:uuid host1), :authkey (:authkey host1)}
          attr1 (ztrc/get-host-attrs zorka-app-state (:uuid host1))
          rslt2 (zorka {:uri "/agent/register", :request-method :post,
                       :data {:rkey "zorka", :name "test.myapp", :uuid (:uuid host1),
                              :akey (:authkey host1),
                              :app "TEST", :env "TEST", :attrs {:B "X", :C "Y"}}})
          host2 (zobj/get-obj obj-store (:uuid host1))
          attr2 (ztrc/get-host-attrs zorka-app-state (:uuid host1))]
      (is (some? host1))
      (is (= rslt1 {:status 201, :headers {}, :body {:type :rest, :data data1}}))
      (is (= "test" (:name host1)))
      (is (= {:A "1", :B "2"} attr1))
      (is (= "DSC" (:name (zobj/get-obj obj-store (:app host1)))))
      (is (= "TST" (:name (zobj/get-obj obj-store (:env host1)))))

      (is (some? host2))
      (is (= rslt2 {:status 200, :headers {}, :body {:type :rest, :data data1}}))
      (is (= "test.myapp" (:name host2)))
      (is (= {:A "1", :B "X", :C "Y"} attr2))
      (is (= "TEST" (:name (zobj/get-obj obj-store (:app host2)))))
      (is (= "TEST" (:name (zobj/get-obj obj-store (:env host2))))))))


; TODO test REST interfaces for viewing and editing objects

; TODO test REST interface for editing configuration properties

; TODO check if trace IDs updated in trace store when autoregistering/editing trace type

; TODO Check if app/env IDs updated in trace store when editing objects

