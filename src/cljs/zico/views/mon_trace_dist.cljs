(ns zico.views.mon-trace-dist
  (:require
    [reagent.ratom :as ra]
    [zico.widgets :as zw]
    [zico.views.common :as zv]
    [zico.views.mon-trace :as zvmt]
    [zico.state :as zs]
    [clojure.string :as cs]
    [zico.io :as io]))


(defn dtrace-path-compare [p1 p2]
  (cond
    (empty? p1) -1
    (empty? p2) 1
    (< (first p1) (first p2)) -1
    (> (first p1) (first p2)) 1
    (= (first p1) (first p2)) (dtrace-path-compare (rest p1) (rest p2))
    :else 0))


(defn dtrace-compare [t1 t2]
  (dtrace-path-compare (:dtrace-path t1) (:dtrace-path t2)))


(defn prep-dtrace-tree-results [rslt dt-out]
  (sort
    dtrace-compare
    (for [r (vals rslt)
          :when (and (:dtrace-tid r) (or dt-out (not (:dtrace-out r))))
          :let [segs (rest (cs/split (:dtrace-tid r "") #"_"))]]
      (assoc r
        :dtrace-level (+ (count segs) (if (:dtrace-out r) 1 0))
        :dtrace-path (for [s segs] (js/parseInt s 16))))))


(zs/register-sub
  :data/dtrace-tree-list
  (fn [db _]
    (ra/reaction
      (prep-dtrace-tree-results
        (get-in @db [:data :dtrace :tree])
        (get-in @db [:view :dtrace :tree :dtrace-out] false)))))


(def DISPLAY-DTRACE-OUT (zs/subscribe [:get [:view :dtrace :tree :dtrace-out]]))


(defn dtrace-toolbar-left []
  [:div.flexible.flex
   (zw/svg-button
     :awe :eye-off :light "Suppress details"
     [:toggle [:view :trace :list :suppress]]
     :opaque zvmt/SUPPRESS-DETAILS)
   (zw/svg-button
     :awe :link-ext :light "Display output traces."
     [:toggle [:view :dtrace :tree :dtrace-out]]
     :opaque DISPLAY-DTRACE-OUT)
   ])


(defn dtrace-tree [{:keys [dtrace-uuid]}]
  "Displays distributed trace panel [:view :trace :dtrace]"
  (zs/dispatch-sync
    [:set [:data :dtrace :tree] []])
  (zs/dispatch
    [:xhr/post (io/api "/trace") nil
     {:limit 1000, :offset 0 :qmi {:dtrace-uuid dtrace-uuid}}
     :on-success [:set [:data :dtrace :tree]],
     :on-error zv/DEFAULT-SERVER-ERROR,
     :map-by :uuid])
  (zv/render-screen
    :hide-menu-btn true
    :toolbar [zv/list-screen-toolbar
              :vpath [:view :dtrace :tree]
              :title     "Distributed tracing",
              :sort-ctls {}
              :add-left  [dtrace-toolbar-left]]
    :central [zv/list-interior
              :vpath [:view :dtrace :tree]
              :data [:data/dtrace-tree-list]
              :render-item (zvmt/render-trace-list-item-fn :dtrace-links false)
              :render-details (zvmt/render-trace-list-detail-fn false false)
              :id-attr :uuid,
              :id "zorka-dist",
              :class "trace-list-list",
              :on-click (zvmt/trace-list-click-handler-fn :dtrace :tree)]
    ))

