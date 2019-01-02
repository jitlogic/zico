(ns zico.webapi-test
  (:require
    [zico.web :as zweb]
    [zico.server :as zsvr :refer [zorka-app-state]]
    [zico.test-util :refer
     [zorka-integ-fixture *root-path* time-travel zorka obj-store trace-store]]
    [clojure.test :refer :all]
    [zico.objstore :as zobj]
    [zico.trace :as ztrc]
    [clojure.data.json :as json])
  (:import (io.zorka.tdb.store TraceStore)))


(use-fixtures :each zorka-integ-fixture)


(defn rest-post [uri data]
  (let [r (zorka {:uri     uri, :request-method :post,
                  :scheme  "http"
                  :headers {"content-type" "application/edn"}
                  :body    (json/write-str data)})]
    (if (some? (:body r)) (assoc r :body (json/read-str (:body r) :key-fn keyword)) r)))


(deftest test-agent-registration
  (testing "Register and check if record is present in object store."
    (let [rslt (rest-post "/agent/register" {:rkey "zorka", :name "test"})
          host (zobj/find-and-get-1 obj-store {:class :host, :name "test"})
          data {:id (:id host), :authkey (:authkey host)}]
      (is (some? host))
      (is (= (dissoc rslt :headers) {:status 200, :body data}))
      (is (= "test" (:name host)))
      (is (= {} (ztrc/get-host-attrs zorka-app-state (:uuid host))))
      (is (= "APP" (:name (zobj/get-obj obj-store {:class :app, :id (:app host)}))))
      (is (= "PRD" (:name (zobj/get-obj obj-store {:class :env, :id (:env host)})))))))


(deftest test-agent-register-custom-app-env
  (testing "Register with custom application and environment."
    (let [seq-a (reduce max 0 (zobj/find-obj obj-store {:class :app}))
          seq-e (reduce max 0 (zobj/find-obj obj-store {:class :app}))
          rslt (rest-post "/agent/register" {:rkey "zorka", :name "test1", :app "TEST", :env "TEST"})
          {:keys [app env] :as host} (zobj/find-and-get-1 obj-store {:class :host, :name "test1"})
          app-t (zobj/find-and-get-1 obj-store {:class :app, :name "TEST"})
          env-t (zobj/find-and-get-1 obj-store {:class :app, :name "TEST"})
          data {:uuid (:uuid host), :authkey (:authkey host)}]
      (println rslt)
      (is (some? host))
      ;(is (= {:status 201, :headers {"content-type" "application/edn"}, :data data} (dissoc rslt :body)))
      (is (= {:class :app, :id app, :name "TEST", :comment "New :app",
              :flags 1, :glyph "awe/cube"}
             (zobj/get-obj obj-store {:class :app, :id app})))
      (is (= {:class :env, :id env, :name "TEST", :comment "New :env",
              :flags 1, :glyph "awe/cube"}
             (zobj/get-obj obj-store {:class :env, :id env})))
      (is (some? app-t) "New app TEST should be created.")
      ;(is (> (zobj/extract-uuid-seq (:uuid app-t)) seq-a) "App TEST should have highest seq num.")
      (is (some? env-t) "New env TEST should be created.")
      ;(is (> (zobj/extract-uuid-seq (:uuid env-t)) seq-e) "Env TEST should have highest seq num.")
      )))


(deftest test-agent-register-custom-attrs
  (testing "Register with custom attributes and check if saved properly."
    (let [req {:rkey "zorka", :name "test2" :attrs {:LOCATION "PL-DC1", :ROOM "3"}},
          rslt (rest-post "/agent/register" req)
          host (zobj/find-and-get-1 obj-store {:class :host, :name "test2"}),
          data {:id (:id host), :authkey (:authkey host)},
          attr1 (zobj/find-and-get-1 obj-store {:class :attrdesc, :name "LOCATION"})
          attr2 (zobj/find-and-get-1 obj-store {:class :attrdesc, :name "ROOM"})]
      (is (some? host))
      ;(is (= rslt {:status 201, :headers {}, :body data}))
      (is (some? attr1))
      (is (some? attr2))
      (let [q1 {:class :hostattr, :attrid (:id attr1), :hostid (:id host)}
            q2 {:class :hostattr, :attrid (:id attr2), :hostid (:id host)}]
        (is (= "PL-DC1" (:attrval (zobj/find-and-get-1 obj-store q1))))
        (is (= "3" (:attrval (zobj/find-and-get-1 obj-store q2)))))
      (is (= {:LOCATION "PL-DC1", :ROOM "3"} (ztrc/get-host-attrs zorka-app-state (:id host)))))))


(deftest test-agent-session
  (testing "Register agent and obtain session."
    (let [rrslt (rest-post "/agent/register" {:rkey "zorka", :name "test"})
          host (zobj/find-and-get-1 obj-store {:class :host, :name "test"})
          rdata {:id (:id host), :authkey (:authkey host)}
          srslt (zorka {:uri  "/agent/session", :request-method :post, :scheme "http",
                        :body (json/write-str {:id      (-> rrslt :body :data :id),
                                               :authkey (-> rrslt :body :data :authkey)})})
          sdata {:session (.getSession ^TraceStore trace-store (str (:id host)))}]
      (is (some? host))
      (is (some? sdata))
      (is (string? (:session sdata)))
      ;(is (= rrslt {:status 201, :headers {}, :body {:type :rest, :data rdata}}))
      ;(is (= srslt {:status 200, :headers {}, :body {:type :rest, :data sdata}}))
      )))


;(deftest test-agent-register-update-data-attrs
;  (testing "Register agent, then register again with different registration data."
;    (let [rslt1 (zorka {:uri  "/agent/register", :request-method :post,
;                      :data {:rkey "zorka", :name "test", :attrs {:A "1", :B "2"}}})
;          host1 (zobj/find-and-get-1 obj-store {:class :host, :name "test"})
;          data1 {:uuid (:uuid host1), :authkey (:authkey host1)}
;          attr1 (ztrc/get-host-attrs zorka-app-state (:uuid host1))
;          rslt2 (zorka {:uri "/agent/register", :request-method :post,
;                       :data {:rkey "zorka", :name "test.myapp", :uuid (:uuid host1),
;                              :akey (:authkey host1),
;                              :app "TEST", :env "TEST", :attrs {:B "X", :C "Y"}}})
;          host2 (zobj/get-obj obj-store (:uuid host1))
;          attr2 (ztrc/get-host-attrs zorka-app-state (:uuid host1))]
;      (is (some? host1))
;      ;(is (= rslt1 {:status 201, :headers {}, :body {:type :rest, :data data1}}))
;      (is (= "test" (:name host1)))
;      ;(is (= {:A "1", :B "2"} attr1))
;      (is (= "DSC" (:name (zobj/get-obj obj-store (:app host1)))))
;      (is (= "TST" (:name (zobj/get-obj obj-store (:env host1)))))
;
;      (is (some? host2))
;      (is (= rslt2 {:status 200, :headers {}, :body {:type :rest, :data data1}}))
;      (is (= "test.myapp" (:name host2)))
;      (is (= {:A "1", :B "X", :C "Y"} attr2))
;      (is (= "TEST" (:name (zobj/get-obj obj-store (:app host2)))))
;      (is (= "TEST" (:name (zobj/get-obj obj-store (:env host2))))))))
;

; TODO test REST interfaces for viewing and editing objects

; TODO test REST interface for editing configuration properties

; TODO check if trace IDs updated in trace store when autoregistering/editing trace type

; TODO Check if app/env IDs updated in trace store when editing objects

