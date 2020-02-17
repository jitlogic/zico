(ns zico.small-functions-unit-test
  (:require
    [clojure.test :refer :all]
    [zico.util :as zu]
    [zico.elastic :as zela]))

(def ATTR-TRANSFORMS-1
  [{:match #"foo.*", :replace "bar"}
   {:match #"(par_[a-z]+)_j_.*", :replace "$1_xxx"}])

(deftest test-transform-attr-keys
  (is (= "bar" (zela/attr-key-transform ATTR-TRANSFORMS-1 "foo.1")))
  (is (= "par_foo_xxx" (zela/attr-key-transform ATTR-TRANSFORMS-1 "par_foo_j_idt151"))))

(deftest test-group-map
  (is (= {"foo" ["bar" "baz"], "FOO" "BAR"}
         (zu/group-map [["foo" "bar"] ["foo" "baz"] ["FOO" "BAR"]]))))

