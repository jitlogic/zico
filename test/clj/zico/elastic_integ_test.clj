(ns zico.elastic-integ-test
  (:require
    [zico.elastic :as ze]
    [clojure.test :refer :all]
    [clj-http.client :as http]))

(def ^:dynamic *DB*
  {:url "http://127.0.0.1:9200"
   :name "zicotest"})

(defn elastic-ping []
  (try
    (let [rslt (http/get (:url *DB*))]
      (= 200 (:status rslt)))
    (catch Exception _ false)))

(defn elastic-integ-fixture [f]
  (if (elastic-ping)
    (do
      (doseq [idx (ze/list-indexes *DB*)]
        (ze/index-delete *DB* (:tsnum idx)))
      (ze/index-create *DB* 1)
      (f))
    (do
      (println "WARNING: test not executed as ElasticSearch instance was not present.")
      (println "Please run Elastic OSS instance in its default configuration on " (:url *DB*)))))

(use-fixtures :each elastic-integ-fixture)

(deftest test-map-resolve-symbols
  (let [ma {42 "foo", 88 "bar"}
        mm (ze/syms-map *DB* 1 ma)
        ms (ze/syms-search *DB* 1 (set (vals ma)))
        mr (ze/syms-resolve *DB* 1 (vals mm))
        ma2 {99 "foo", 77 "bar"}
        mm2 (ze/syms-map *DB* 1 ma2)
        mr2 (ze/syms-resolve *DB* 1 (vals mm2))]
    (is (contains? ms "foo"))
    (is (contains? ms "bar"))
    (is (= "foo", (mr (mm 42))))
    (is (= "bar", (mr (mm 88))))
    (is (= "foo" (mr2 (mm2 99))))
    (is (= "bar" (mr2 (mm2 77))))
    (is (= (mm 42) (mm2 99)))
    (is (= (mm 88) (mm2 77)))))

