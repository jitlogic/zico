(ns zico.views.mon-trace-tree
  (:require
    [reagent.ratom :as ra]
    [zico.views.common :as zv]
    [zico.state :as zs]
    [zico.widgets :as zw]
    [zico.views.mon-trace :as zvmt]
    [zico.util :as zu]
    [cljs.pprint :as pp]))


(defn flatten-trace [collapsed t0 lvl {:keys [children duration pos] :as tr}]
  (let [open (not (collapsed pos)), state (cond (nil? children) nil, open :open, :else :closed)]
    (cons
      (assoc (dissoc tr :children) :level lvl, :state state, :pct (* 100 (/ duration t0)))
      (when (and children open)
        (reduce concat
                (for [c children]
                  (flatten-trace collapsed t0 (inc lvl) c)))))))


(defn trace-tree-list-ra [db [_]]
  (let [data (ra/reaction (get-in @db [:data :trace :tree]))
        collapsed (ra/reaction (get-in @db [:view :trace :tree :collapsed]))]
    (ra/reaction
      (let [tr @data]
        (when tr (flatten-trace (or @collapsed {}) (:duration tr 1) 1 tr))
        ))))


(defn init-collapse-nodes [t0 pmin {:keys [children duration pos] :as tr}]
  (let [pct (/ duration t0)]
    (apply
      merge
      (if (< pct pmin) {pos true})
      (for [c children :when (:children c)]
        (init-collapse-nodes t0 pmin c)))))


(zs/register-sub
  :data/trace-tree-list
  trace-tree-list-ra)


(zs/reg-event-fx
  ::display-tree
  (fn [{:keys [db]} [_ uuid]]
    {:db (-> db
             (assoc-in [:view :trace :tree] {:uuid uuid, :collapsed {}})
             (assoc-in [:data :trace :tree] nil))
     :dispatch-n
         [[:to-screen "mon/trace/tree"]
          [:xhr/get (str "../../../data/trace/" uuid "/tree") nil nil,
           :on-success [::handle-xhr-result nil]]]}))


(zs/reg-event-db
  ::handle-xhr-result
  (fn [db [_ _ tr]]
    (let [cl (init-collapse-nodes (:duration tr 15259) 0.1 tr)]
      (println (str cl))
      (->
        db
        (assoc-in [:data :trace :tree] tr)
        (assoc-in [:view :trace :tree :collapsed] cl)
        ))))


(defn pct-color [pct]
  (cond
    (> pct 90) "#f00"
    (> pct 80) "#f33"
    (> pct 60) "#e44"
    (> pct 40) "#d66"
    (> pct 20) "#c99"
    :else "#aaa"
    ))

(defn render-trace-tree-detail [{{:keys [result package class method args]} :method
                                 :keys [pos attrs duration exception pct] :as tr}]
  ^{:key pos}
  [:div.det {:data-trace-pos pos}
   [:div.method
    [:div.c-darker.flexible result]
    [:div.ti {:style {:color (pct-color pct)}} (zw/svg-icon :awe :clock :blue) [:div.flexible]
     (str (zu/ticks-to-str duration) " (" (pp/cl-format nil "~,2f" pct) "%)")]]
   [:div.c-light.ellipsis (str method args)]
   [:div.c-darker.text-rtl.ellipsis package "." class]
   (when attrs (zvmt/render-attrs attrs nil))
   (when exception (zvmt/render-exception exception true))])


(defn render-trace-tree-item [{{:keys [result package class method args]} :method,
                               :keys [pos level duration attrs exception state pct] :as tr}]
  ^{:key pos}
  [:div.itm.method {:data-trace-pos pos}
   [:div.flex {:style {:color (pct-color pct)}}
    [:div.t (zu/ticks-to-str duration)]
    ;[:div.p (if (= pct 100.0) "100.0" (pp/cl-format nil "~,2f" pct))]
    ]
   [(cond attrs :div.mc.c-blue exception :div.mc.c-red :else :div.mc.c-text)
    {:style {:margin-left (str (* level 10) "px")}}
    [:div.flex.ml
     (case state
       :open (zw/svg-button :awe :minus-squared-alt :text "Collapse" [:toggle [:view :trace :tree :collapsed pos]])
       :closed (zw/svg-button :awe :plus-squared-alt :text "Expand" [:toggle [:view :trace :tree :collapsed pos]])
       (zw/svg-icon :awe :null :text))
     [:div.mr.medium-or-more result]
     [:div.large-or-more (str package ".")]
     [:div.small-or-more (str class ".")]
     [:div.m.medium-or-less (str method (if (= args "()") "()" "(...)"))]
     [:div.m.medium-or-more (str method args)]]]])


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
              {:title     "Call tree",
               :sort-ctls {}
               :flags     #{:no-refresh :no-bookmark}
               :add-left  [toolbar-tree-left]
               :add-right [toolbar-tree-right]}]
    :central [zv/list-interior [:trace :tree] render-trace-tree-item render-trace-tree-detail
              :id-attr :pos, :id "zorka-methods", :on-click trace-tree-click-handler]))
