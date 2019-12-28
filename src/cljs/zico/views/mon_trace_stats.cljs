(ns zico.views.mon-trace-stats
  (:require
    [reagent.ratom :as ra]
    [zico.views.common :as zv]
    [zico.widgets.state :as zs]
    [zico.widgets.widgets :as zw]
    [zico.views.mon-trace :as zvmt]
    [zico.widgets.io :as io]
    [zico.widgets.screen :as zws]
    [zico.widgets.util :as zu]))


(defn trace-stats-list-ra [_ [_]]
  (let [stats (zs/subscribe [:get [:data :trace :stats]])
        order (zs/subscribe [:get [:view :trace :stats :sort]])]
    (ra/reaction
      (reverse (sort-by (if (:duration @order) :sum-duration :recs) @stats))
      )))


(zs/register-sub
  :data/trace-stats-list
  trace-stats-list-ra)


(defn render-trace-stats-item [{:keys [mid method recs errors sum-duration min-duration max-duration] :as ts}]
  (let [tt (str (int (/ (/ (* 65536 sum-duration) recs) 1000000)))]
    ^{:key mid}
    [:div.itm.mstat
     [:div.flex.n
      (zw/svg-icon :awe :inbox :green, :title "Method calls recorded")
      [:div.t (str recs)]]
     [:div.flex.n
      (zw/svg-icon :awe :clock :blue, :title "Average execution time (ms)")
      [:div.t tt]]
     [:div.m method]]))


(defn toolbar-stats-left []
  (let [sort-r (zs/subscribe [:get [:view :trace :stats :sort :recs]])
        sort-d (zs/subscribe [:get [:view :trace :stats :sort :duration]])]
    (fn []
      [:div.flexible.flex
       (zw/svg-button
         :awe :inbox :green "Sort by number of recorded calls"
         [:set [:view :trace :stats :sort] {:recs true}]
         :opaque sort-r)
       (zw/svg-button
         :awe :clock :blue "Sort by summary duration"
         [:set [:view :trace :stats :sort] {:duration true}]
         :opaque sort-d
         )]
      )))


(defn trace-stats [{{:keys [tid]} :params}]
  "Trace call stats panel [:view :trace :stats]"
  (when-let [{:keys [traceid spanid]} (zu/parse-tid tid)]
    (zs/dispatch-sync
      [:set [:data :trace :stats] nil])
    (zs/dispatch
      [:xhr/get (io/api "/trace/" traceid "/" spanid "/stats")
       [:data :trace :stats] nil
       :on-error zv/DEFAULT-SERVER-ERROR]))
  (zws/render-screen
    :hide-menu-btn true
    :toolbar [zws/list-screen-toolbar
              :vpath [:view :trace :stats]
              :title     "Method Call Stats"
              :sort-ctls {}
              :add-left  [toolbar-stats-left]]
    :central [zws/list-interior
              :vpath [:view :trace :stats]
              :data [:data/trace-stats-list]
              :render-item render-trace-stats-item
              :render-details render-trace-stats-item
              :id-attr :mid,
              :class "trace-stats-list",
              :id "zorka-method-stats"]))

(zws/defscreen "mon/trace/stats" trace-stats)
