(ns zico.small-functions-unit-test
  (:require
    [clojure.test :refer :all]
    [zico.util :as zu]))


(deftest test-group-map
  (is (= {"foo" ["bar" "baz"], "FOO" "BAR"}
         (zu/group-map [["foo" "bar"] ["foo" "baz"] ["FOO" "BAR"]]))))

