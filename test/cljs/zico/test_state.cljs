(ns zico.test-state
  (:require
    [cljs.test :refer-macros [is are deftest testing use-fixtures]]
    [day8.re-frame.test :as rft]
    [re-frame.core :as rf]
    [zico.views.cfg :as zvc]
    [zico.state :as zs]
    [zico.test-common :as tc]))


(deftest test-init-state
  (testing "Check initial state."
    (rft/run-test-sync
      (tc/setup-fixture)
      (let [view (rf/subscribe [:get [:view]])]
        (is (map? @view))))))


(deftest test-state-set-get
  (testing "Basic getters and setters for state manipulation."
    (rft/run-test-sync
      (tc/setup-fixture)
      (rf/dispatch [:set [:view :mon :trace :selected] "123"])
      (is (= "123" @(rf/subscribe [:get [:view :mon :trace :selected]]))))))


(deftest test-xhr-get
  (rft/run-test-sync
    (tc/setup-fixture)
    (let [h (rf/subscribe [:get [:data :cfg :host]])]
      (testing "Simplest GET operation returns list."
        (rf/dispatch [:xhr/get "data/cfg/host" [:data :cfg :host] nil])
        (is (list? @h))
        (is (map? (first @h)))
        (is (= :host (:class (first @h)))))
      (testing "GET operation with mapping by UUID"
        (rf/dispatch [:xhr/get "data/cfg/host" [:data :cfg :host] nil :map-by :uuid])
        (is (map? @h))
        (is (= :host (:class (second (first @h))))))
      (testing "GET operation - fetch single record"
        (rf/dispatch [:xhr/get "/data/cfg/host/21c00000-0501-0000-0001-0165413137c6" [:data :cfg :host] nil])
        (is (map? @h))
        (is (= "21c00000-0501-0000-0001-0165413137c6" (:uuid @h))))
      )))


(deftest test-xhr-post-put-delete
  (rft/run-test-sync
    (tc/setup-fixture)
    (let [r (rf/subscribe [:get [:test :ret]])
          x (rf/subscribe [:get [:test :xhr :data :cfg :host]])]
      (testing "Insert new record via XHR"
        (rf/dispatch [:xhr/post "data/cfg/host" nil {:name "test", :comment "some test"}
                      :on-success [:set [:test :ret]]])
        (is (map? @r))
        (is (= 2 (count @x)))
        (let [m (get @x (:uuid @r))]
          (is (map? m))
          (is (= "test" (:name m)))))
      (testing "Updating record via XHR"
        (let [uuid (:uuid @r)]
          (rf/dispatch [:xhr/put (str "data/cfg/host/" uuid) nil {:uuid uuid :name "test2"}])
          (is (= 2 (count @x)))
          (let [m (@x uuid)]
            (is (map? m))
            (is (= "test2" (:name m))))))
      (testing "Deleting record via XHR"
        (let [uuid (:uuid @r)]
          (rf/dispatch [:xhr/delete (str "data/cfg/host/" uuid) nil nil])
          (is (= 1 (count @x)))
          (is (nil? (@x (:uuid r))))))
      )))


(deftest test-form-edit-new-cancel
  (testing "Basic form editing operations."
    (rft/run-test-sync
      (tc/setup-fixture)
      (let [state (rf/subscribe [:get [:view :cfg :host]])
            screens (rf/subscribe [:get [:test :io :to-screen]])]
        (rf/dispatch [:form/edit-new :cfg :host zvc/HOST-TEMPLATE zvc/HOST-FDEFS])
        (is (= :new (-> @state :edit :uuid)))
        (is (= "cfg/host/edit" (first (first @screens))))
        (rf/dispatch [:form/edit-cancel :cfg :host])
        (is (= "cfg/host/list" (first (first @screens))))
        ))))

