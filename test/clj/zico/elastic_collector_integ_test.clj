(ns zico.elastic-collector-integ-test
  (:require
    [clojure.test :refer :all]
    [zico.test-util :refer [*DB*] :as tu]
    [zico.trace :as ztrc]
    [zico.main :as zsvr]
    [zico.util :as zu]
    [zico.elastic :as ze]))

(use-fixtures :each tu/elastic-integ-fixture tu/zorka-integ-fixture)

(def AGD1
  (tu/trace-data
    [[:sref 41 "component"]
     [:sref 42 "mydb.PStatement"] [:sref 43 "execute"] [:sref 44 "V()"]
     [:sref 45 "db"] [:sref 46 "invoke"] [:sref 47 "myweb.Valve"]
     [:sref 48 "http"] [:sref 49 "my.Class"] [:sref 50 "myMethod"]
     [:sref 51 "db.statement"] [:sref 52 "db.type"] [:sref 53 "db.url"]
     [:sref 54 "local.host"] [:sref 55 "local.pid"] [:sref 56 "local.service"]
     [:sref 57 "thread.id"] [:sref 58 "thread.name"]
     [:mref 11 42 43 44]
     [:mref 12 47 46 44]
     [:mref 13 49 50 44]]))

(def TRC1
  (tu/trace-data
    [:start 0 100 11
     [:begin 1000 45 0x1234567812345001 0]
     [:attr 41 "db"] [:attr 51 "SET SCHEMA PUBLIC"] [:attr 52 "sql"] [:attr 53 "jdbc:h2:mem:customers"]
     [:attr 54 "localhost"] [:attr 55 "99158"] [:attr 56 "customers"] [:attr 57 "42"] [:attr 58 "main"]
     [:end 0 200 2 0]]))

(deftest test-submit-search-simple-trace
  (testing "Submit and search simple trace"
    (is (= 202 (:status (ztrc/submit-agd zsvr/zorka-app-state "1234" true AGD1))))
    (is (= 202 (:status (ztrc/submit-trc zsvr/zorka-app-state "1234" "9234567812345001" 0 TRC1))))
    (ze/index-refresh *DB* 1)
    (let [[r1 & _] (ztrc/trace-search zsvr/zorka-app-state {})]
      (is (some? r1))
      (is (= 100 (:duration r1)))
      (is (= 1 (:recs r1)))
      (is (= 3 (:calls r1)))
      (is (= 1 (:tsnum r1)))
      (is (= "1970-01-01T00:00:01" (:tstamp r1)))
      (is (= "9234567812345001" (:traceid r1)))
      (is (= "1234567812345001" (:spanid r1))))))

(deftest test-submit-retrieve-simple-trace
  (testing "Submit and retrieve simple trace"
    (is (= 202 (:status (ztrc/submit-agd zsvr/zorka-app-state "1234" true AGD1))))
    (is (= 202 (:status (ztrc/submit-trc zsvr/zorka-app-state "1234" "9234567812345001" 0 TRC1))))
    (ze/index-refresh *DB* 1)
    (let [r (ztrc/trace-detail zsvr/zorka-app-state "9234567812345001" "1234567812345001")]
      (is (= "mydb.PStatement.execute()" (:method r)))
      (is (= "db" (get-in r [:attrs "component"])))
      (is (= "sql" (get-in r [:attrs "db.type"])))
      (is (= 100 (:duration r)))
      (is (= 100 (:tstart r))))))

(def TRC2
  (tu/trace-data
    [:start 0 100 12
     [:begin 1000 48 0x1234567812345002 0]
     [:attr 41 "http"]
     [:start 0 200 11
      [:begin 1001 45 0x1234567812345003 0]
      [:attr 41 "db"]
      [:end 0 300 2 0]]
     [:start 0 310 13
      [:end 0 350 1 0]]
     [:end 0 400 5 0]]))

(deftest test-submit-retrieve-embedded-trace
  (testing "Submit and retrieve trace with other trace embedded in"
    (is (= 202 (:status (ztrc/submit-agd zsvr/zorka-app-state "1234" true AGD1))))
    (is (= 202 (:status (ztrc/submit-trc zsvr/zorka-app-state "1234" "9234567812345001" 0 TRC2))))
    (ze/index-refresh *DB* 1)
    (let [r (ztrc/trace-detail zsvr/zorka-app-state "9234567812345001" "1234567812345003")]
      (is (= "mydb.PStatement.execute()" (:method r)))
      (is (= "db" (get-in r [:attrs "component"])))
      (is (= 100 (:duration r)))
      (is (= 200 (:tstart r))))
    ))

(deftest test-submit-retrieve-store-with-rotation
  (testing "Submit two traces, rotate index inbetween"
    (is (= 202 (:status (ztrc/submit-agd zsvr/zorka-app-state "1234" true AGD1))))
    (is (= 202 (:status (ztrc/submit-trc zsvr/zorka-app-state "1234" "9234567812345001" 0 TRC2))))
    (println (ze/list-data-indexes *DB*))
    )
  )