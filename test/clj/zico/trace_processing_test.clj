(ns zico.trace-processing-test
  (:require
    [clojure.test :refer :all]
    [zico.test-util :refer [zorka-integ-fixture *root-path* time-travel obj-store]]
    [zico.trace :as ztrc]
    [zico.objstore :as zobj])
  (:import (com.jitlogic.zorka.be.store TraceTypeResolver)))


(use-fixtures :each zorka-integ-fixture)


(deftest test-trace-id-translation
  (testing "Test for existing trace type"
    (let [tit ^TraceTypeResolver (ztrc/trace-id-translator obj-store)
          id1, (.resolve tit "HTTP"), id3 (.resolve tit "SQL")]
      (is (= 1 id1))
      (is (= 3 id3))))
  (testing "Test for newly defined trace type"
    (let [tit ^TraceTypeResolver (ztrc/trace-id-translator obj-store)
          id (.resolve tit "XXX"),
          rec (zobj/find-and-get-1 obj-store {:class :ttype, :name "XXX"})]
      (is (int? id))
      (is (= "Trace type: XXX", (:desc rec))))))

