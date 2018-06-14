(ns zico.objstore-test
  (:require
    [clojure.test :refer :all]
    [zico.server :as zsvr :refer [zorka-app-state]]
    [zico.test-util :refer [zorka-integ-fixture *root-path* time-travel]]
    [zico.objstore :as zobj]
    [clojure.java.jdbc :as jdbc]))


(use-fixtures :each zorka-integ-fixture)


(deftest test-read-default-conf-objs
  (testing "Check if there is database at all"
    (is (some? (:zico-db zorka-app-state))))
  (testing "Check if predefined data is loaded"
    (is (< 0 (count (zobj/find-obj (:obj-store zorka-app-state) {:class :ttype}))))))


(deftest test-modify-objs
  (testing "Modify existing object"
    (let [os (:obj-store zorka-app-state), db (:zico-db zorka-app-state)
          r1 (zobj/get-obj os "21c00000-0101-0000-0001-000000000000")
          _ (zobj/put-obj os (assoc r1 :name "BROMBA"))
          r2 (first (jdbc/query db ["select * from ttype where uuid = ?" (:uuid r1)]))]
      (is (= "BROMBA" (:name r2)))))
  (testing "Insert new object"
    (let [os (:obj-store zorka-app-state), db (:zico-db zorka-app-state)
          r0 {:class :app, :name "TEST", :glyph "some/test", :comment "Some test.", :flags 1}
          r1 (zobj/put-obj os r0)
          uuid (:uuid r1)
          r2 (zobj/get-obj os uuid)
          r3 (first (jdbc/query db ["select * from app where uuid = ?" uuid]))
          _ (zobj/del-obj os uuid)
          r4 (zobj/get-obj os uuid)
          r5 (first (jdbc/query db ["select * from app where uuid = ?" uuid]))]
      (is (= r2 r1))
      (is (= r3 (dissoc r2 :class)))
      (is (nil? r4))
      (is (nil? r5)))))

