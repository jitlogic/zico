(ns zico.elastic-components-integ-test
  (:require
    [zico.elastic :as ze]
    [clojure.test :refer :all]
    [zico.test-util :refer [*DB*] :as tu]))

(use-fixtures :each tu/elastic-integ-fixture)

(deftest test-map-resolve-symbols
  (let [sa {42 "foo", 88 "bar"}
        sm (ze/syms-map *DB* 1 sa)
        ss (ze/syms-search *DB* 1 (set (vals sa)))
        sr (ze/syms-resolve *DB* 1 (vals sm))
        sa2 {99 "foo", 77 "bar"}
        sm2 (ze/syms-map *DB* 1 sa2)
        sr2 (ze/syms-resolve *DB* 1 (vals sm2))]
    (is (contains? ss "foo"))
    (is (contains? ss "bar"))
    (is (= "foo", (sr (sm 42))))
    (is (= "bar", (sr (sm 88))))
    (is (= "foo" (sr2 (sm2 99))))
    (is (= "bar" (sr2 (sm2 77))))
    (is (= (sm 42) (sm2 99)))
    (is (= (sm 88) (sm2 77)))))

(deftest test-map-resolve-methods
  (let [sm (ze/syms-add *DB* 1 ["x.C1" "y.C2" "m1" "m2" "V()" "I(I)"])
        m1 [(sm "x.C1") (sm "m1") (sm "V()")], m2 [(sm "y.C2") (sm "m2") (sm "I(I)")]
        ma {42 m1, 84, m2}
        mm (ze/mids-map *DB* 1 ma)
        ms (ze/mids-search *DB* 1 (set (vals ma)))
        mr (ze/methods-resolve *DB* 1 (vals mm))
        ma2 {11 m1, 22 m2}
        mm2 (ze/mids-map *DB* 1 ma2)
        mr2 (ze/methods-resolve *DB* 1 (vals mm2))]
    (is (contains? ms m1))
    (is (contains? ms m2))
    (is (= "x.C1.m1()" (mr (mm 42))))
    (is (= "y.C2.m2()" (mr (mm 84))))
    (is (= "x.C1.m1()" (mr2 (mm2 11))))
    (is (= "y.C2.m2()" (mr2 (mm2 22))))
    (is (= (mm 42) (mm2 11)))
    (is (= (mm 84) (mm2 22)))))

(deftest test-gen-sequences
  (let [s1 (ze/seq-next *DB* 1 :SYMBOLS 10 4)
        s2 (ze/seq-next *DB* 1 :SYMBOLS 5 4)
        s3 (ze/seq-next *DB* 1 :SYMBOLS 2 4)
        s4 (ze/seq-next *DB* 1 :SYMBOLS 2 4)
        s (concat s1 s2 s3 s4)]
    (is (= (count s) (count (into #{} s))))
    ))

