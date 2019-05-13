(ns zico.views.mon-trace
  (:require
    [reagent.ratom :as ra]
    [clojure.string :as cs]
    [zico.widgets.util :as zu]
    [zico.widgets.state :as zs]
    [zico.widgets.widgets :as zw]
    [zico.views.common :as zv]
    [zico.widgets.io :as io]))


(defn data-list-sfn [sectn view sort-attr]
  (fn [db [_]]
    (let [data (ra/reaction (get-in @db [:data sectn view]))
          srev (ra/reaction (get-in @db [:view sectn view :sort :rev]))]
      (ra/reaction
        (let [rfn (if @srev reverse identity)]
          (rfn (sort-by sort-attr (vals @data)))))
      )))


; Register all needed subscriptions
(zs/register-sub
  :data/trace-type-list
  (data-list-sfn :trace :type :name))


(defn trace-list-click-handler-fn [sect sub]
  (fn [e]
    (zs/traverse-and-handle
      (.-target e) "data-zico-tid" "zorka-traces"
      :btn-details #(zs/dispatch [:to-screen "mon/trace/tree" {:tid %}])
      :btn-dtrace #(zs/dispatch [:to-screen "mon/trace/dtree" {:tid %}])
      :itm #(zs/dispatch [:toggle [:view sect sub :selected] %])
      :det #(zs/dispatch [:toggle [:view sect sub :selected] %]))))


(zs/reg-event-fx
  ::filter-by-attr
  (fn [{:keys [db]} [_ k v ttype]]
    (let [fattrs (-> db :view :trace :list :filter-attrs)
          fattrs (if (= ttype (:ttype fattrs)) fattrs {:ttype ttype})
          fattrs (if (contains? fattrs k) (dissoc fattrs k) (assoc fattrs k v))]
      {:db       (assoc-in db [:view :trace :list :filter-attrs] fattrs),
       :dispatch [:zico.views.mon-trace-list/refresh-list true]})))


(def FILTER-ATTRS (zs/subscribe [:get [:view :trace :list :filter-attrs]]))


(defn render-attrs [attrs ttype]
  "Renders method call attributes (if any)"
  (let [filter-attrs @FILTER-ATTRS]
    [:div.trace-attrs
     (for [[n k v] (map cons (range) (sort-by first attrs))]
       ^{:key n}
       [:div.a
        (when ttype
          (if (contains? filter-attrs k)
            [:div.i
             (zw/svg-button
               :awe :cancel :red "Clear filter ..."
               [:do
                [:dissoc [:view :trace :list :filter-attrs] k]
                [:zico.views.mon-trace-list/refresh-list true]])]
            [:div.i
             (zw/svg-button
               :awe :filter :blue "Filter by ..."
               [:do [::filter-by-attr k v ttype]])]))
        [:div.k {:id (str "clipboard-" k)} k]
        [:div.v v]
        [:div.i.pad-l05
         (zw/svg-button
           :awe :paste :text "Copy attribute to clipboard"
           [:write-to-clipboard v])]])]))


(defn exception-to-string [{:keys [class msg stack]}]
  (str
    class ": " msg
    (cs/join
      "\n"
      (for [{:keys [class method file line]} stack]
        (str " at " class "." method "(" file ":" line ")")))
    "\n"))


(defn render-exception [exception full]
  [:div.trace-record-exception
   [:div.cls
    [:div.lb "Exception:"]
    [:div.cl (:class exception)]
    [:div.i.pad-l05
     (zw/svg-button
       :awe :paste :text "Copy exception to clipboard"
       [:write-to-clipboard (exception-to-string exception)])]]
   [:div.msg (str (:msg exception))]
   [:div.stk
    (for [[{:keys [class method file line] :as e} i] (map vector (:stack exception) (range))]
      ^{:key i} [:div.si
                 [:div.c (str class "." method)]
                 [:div.f (str "(" file ":" line ")")]])]])


(defn render-trace-list-detail-fn [enable-filters dtrace-links]
  (fn [{:keys [trace-id span-id chunk-num parent-id tstamp duration desc recs calls errs attrs error children]
        {{:keys [package method class result args]} :method :as detail} :detail :as t}]
    (let [tid (zu/to-tid t)]
      ^{:key tid}
      [:div.det
       {:data-zico-tid tid}
       [:div.flex-on-medium-or-more [:div tstamp]]
       [:div.c-light.bold.wrapping desc]
       (render-attrs attrs enable-filters)
       (when (:exception detail)
         (render-exception (:exception detail) true))
       [:div.btns
        (zw/svg-icon :awe :flash :yellow) [:div.lbl.small-or-more "Calls:"] [:div.val (str calls)]
        (zw/svg-icon :awe :inbox :green) [:div.lbl.small-or-more "Recs:"] [:div.val (str recs)]
        (zw/svg-icon :awe :bug :red) [:div.lbl.small-or-more "Errors:"] [:div.val (str errs)]
        (zw/svg-icon :awe :clock :blue) [:div.lbl.small-or-more "Time:"] [:div.val (zu/ns-to-str duration false)]
        [:div.flexible.flex]                                ; TODO display trace type
        (zw/svg-button
          :awe :chart-bar :text "Method call stats"
          [:to-screen "mon/trace/stats" {:tid tid}])
        (when (and dtrace-links (seq children))
          (zw/svg-button
            :ent :flow-cascade :blue "Distributed trace"
            [:to-screen "mon/trace/dtree" {:tid tid}]))
        (zw/svg-button
          :awe :right-big :blue "View trace details"
          [:to-screen "mon/trace/tree" {:tid tid}]
          )]])))


(def SHOW-DETAILS (zs/subscribe [:get [:view :trace :list :suppress]]))

(defn render-trace-list-item-fn [& {:keys [dtrace-links]}]
  (fn [{:keys [trace-id span-id chunk-num tstamp attrs parent-id depth desc duration recs calls errs error children] :as t}]
    (let [tid (zu/to-tid t), [_ t] (cs/split tstamp #"T") [t _] (cs/split t #"\.")]
      ^{:key tid}
      [:div.itm
       {:data-zico-tid tid}
       [:div.seg
        [:div.ct t]
        [:div.flexible]
        [:div.seg
         {:style {:padding-left (str (* 16 (or depth 0)) "px")}}
         (zw/svg-icon-2 [:awe :paw :text] [:awe :right-dir :green])]
        ;[:div.svg-icon.btn-details.small-or-less.clickable " "]
        ]
       [:div.seg.flexible

        [(if error :div.c2.c-red :div.c2.c-text) desc]]
       [:div.seg
        (zw/svg-icon :awe :clock :blue) (zu/ns-to-str duration false)
        (when @SHOW-DETAILS
          [:div.flex
           (zw/svg-icon :awe :flash :yellow) (str calls)
           (zw/svg-icon :awe :inbox :green) (str recs)
           (zw/svg-icon :awe :bug :red) (str errs)])
        (cond
          (not dtrace-links)
          (zw/svg-icon
            :ent :flow-cascade  :none)
          (seq children)
          (zw/svg-icon
            :ent :flow-cascade :blue,
            :class " clickable btn-dtrace",
            :title "View distributed trace")
          :else
          (zw/svg-icon
            :ent :flow-cascade  :dark
            :title "This is single trace"))
        (zw/svg-icon
          :awe :right-big :blue,
          :class " clickable btn-details",
          :title "View trace details")
        ]])))


