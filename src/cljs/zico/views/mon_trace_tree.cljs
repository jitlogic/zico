(ns zico.views.mon-trace-tree
  (:require
    [zico.views.common :as zv]
    [zico.state :as zs]
    [zico.widgets :as zw]
    [zico.views.mon-trace :as zvmt]
    [zico.util :as zu]))



(defn flatten-trace [t0 lvl {:keys [children duration] :as tr}]
  (cons
    (assoc (dissoc tr :children) :level lvl :pct (* 100 (/ duration t0)))
    (when children
      (reduce concat
              (for [c children]
                (flatten-trace t0 (inc lvl) c))))))


(defmethod zs/rest-process ::display-tree [_ tr]
  (when tr (flatten-trace (:duration tr) 1 tr)))


(zs/reg-event-fx
  ::display-tree
  (fn [{:keys [db]} [_ uuid]]
    {:db (-> db
             (assoc-in [:view :trace :tree] {:uuid uuid})
             (assoc-in [:data :trace :tree] nil))
     :dispatch-n
         [[:to-screen "mon/trace/tree"]
          [:xhr/get (str "../../../data/trace/" uuid "/tree")
           [:data :trace :tree] nil,
           :proc-by ::display-tree]]}))


(defn render-trace-tree-detail [{:keys [pos attrs duration exception]
                                 {:keys [result package class method args]} :method}]
  ^{:key pos}
  [:div.det {:data-trace-pos pos}
   [:div.method
    [:div.c-darker.flexible result]
    [:div.ti (zw/svg-icon :awe :clock :blue) [:div.flexible] (zu/ticks-to-str duration)]]
   [:div.c-light.ellipsis (str method args)]
   [:div.c-darker.text-rtl.ellipsis package "." class]
   (when attrs (zvmt/render-attrs attrs nil))
   (when exception (zvmt/render-exception exception true))])


(defn render-trace-tree-item [{:keys [pos level duration attrs exception]
                               {:keys [result package class method args]} :method}]
  ^{:key pos}
  [:div.itm.method {:data-trace-pos pos}
   [:div.t (zu/ticks-to-str duration)]
   [(cond attrs :div.mc.c-blue exception :div.mc.c-red :else :div.mc.c-text)
    {:style {:margin-left (str (* level 10) "px")}}
    [:div.mr.medium-or-more result]
    [:div.large-or-more (str package ".")]
    [:div.small-or-more (str class ".")]
    [:div.m.medium-or-less (str method (if (= args "()") "()" "(...)"))]
    [:div.m.medium-or-more (str method args)]]])


(defn toolbar-tree-left []
  (let [view-state (zs/subscribe [:get [:view :trace :tree]])]
    (fn []
      (let [view-state @view-state]
        [:div.flexible
         (zw/svg-button
           :awe :left-big :blue "To trace list"
           [:event/pop-dispatch zvmt/TRACE_HISTORY [:to-screen "mon/trace/list"]])]))))


(defn toolbar-tree-right []
  (let [view-state (zs/subscribe [:get [:view :trace :tree]])]
    (fn []
      (let [view-state @view-state]
        [:div.flexible
         (zw/svg-button :awe :attention-circled :red "Errors only"
                        [:toggle [:view :trace :tree :filter :err :selected]]
                        :opaque (get-in view-state [:filter :err :selected]))]
        ))))


(defn trace-tree-click-handler [e]
  (zs/traverse-and-handle
    (.-target e) "data-trace-pos" "zorka-methods"
    :itm #(zs/dispatch [:toggle [:view :trace :tree :selected] (js/parseInt %)])
    :det #(zs/dispatch [:toggle [:view :trace :tree :selected] (js/parseInt %)])))


(defn trace-tree []
  "Trace call tree display panel [:view :trace :tree]"
  (zv/render-screen
    :hide-menu-btn true
    :toolbar [zv/list-screen-toolbar :trace :tree
              {:title "Call tree",
               :sort-ctls {}
               :flags #{:no-refresh :no-bookmark}
               :add-left [toolbar-tree-left]
               :add-right [toolbar-tree-right]}]
    :central [zv/list-interior [:trace :tree] render-trace-tree-item render-trace-tree-detail
              :id-attr :pos, :id "zorka-methods", :on-click trace-tree-click-handler]))
