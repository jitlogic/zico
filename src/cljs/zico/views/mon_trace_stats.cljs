(ns zico.views.mon-trace-stats
  (:require
    [reagent.ratom :as ra]
    [zico.views.common :as zv]
    [zico.state :as zs]
    [zico.widgets :as zw]
    [zico.views.mon-trace :as zvmt]))


(zs/reg-event-fx
  ::display-stats
  (fn [{:keys [db]} [_ uuid]]
    {:db (assoc-in db [:data :trace :stats] nil),
     :dispatch-n
         [[:to-screen "mon/trace/stats"]
          [:xhr/get (str "../../../data/trace/" uuid "/stats")
           [:data :trace :stats] nil
           :on-error zv/DEFAULT-SERVER-ERROR]]}))


(defn trace-stats-list-ra [db [_]]
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
         :awe :left-big :blue "To trace list"
         [:event/pop-dispatch zvmt/TRACE_HISTORY [:to-screen "mon/trace/list"]])
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


(defn trace-stats []
  "Trace call stats panel [:view :trace :stats]"
  (zv/render-screen
    :hide-menu-btn true
    :toolbar [zv/list-screen-toolbar :trace :stats
              {:title     "Method Call Stats"
               :sort-ctls {}
               :flags     #{:no-refresh :no-bookmark}
               :add-left  [toolbar-stats-left]
               }]
    :central [zv/list-interior [:trace :stats] render-trace-stats-item render-trace-stats-item
              :id-attr :mid, :id "zorka-method-stats"]))