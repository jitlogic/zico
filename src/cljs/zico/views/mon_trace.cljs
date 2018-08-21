(ns zico.views.mon-trace
  (:require
    [reagent.ratom :as ra]
    [clojure.string :as cs]
    [zico.util :as zu]
    [zico.state :as zs]
    [zico.widgets :as zw]))


; Register all needed subscriptions
(zs/register-sub
  :data/trace-type-list
  (zs/data-list-sfn :trace :type :name))




(def CFG-TTYPES (zs/subscribe [:get [:data :cfg :ttype]]))
(def CFG-HOSTS (zs/subscribe [:get [:data :cfg :host]]))
(def CFG-ENVS (zs/subscribe [:get [:data :cfg :env]]))
(def CFG-APPS (zs/subscribe [:get [:data :cfg :app]]))

(def TRACE_HISTORY [:view :trace :history])

(defn trace-list-click-handler-fn [sect sub]
  (fn [e]
    (zs/traverse-and-handle
      (.-target e) "data-trace-uuid" "zorka-traces"
      :btn-details #(zs/dispatch [:event/push-dispatch TRACE_HISTORY [:zico.views.mon-trace-tree/display-tree %]])
      :btn-dtrace #(zs/dispatch [:event/push-dispatch TRACE_HISTORY [:zico.views.mon-trace-dist/dtrace-tree %]])
      :itm #(zs/dispatch [:do [:toggle [:view sect sub :selected] %]
                          [:xhr/get (str "../../../data/trace/" % "/detail")
                           [:data sect sub % :detail] nil]])
      :det #(zs/dispatch [:toggle [:view sect sub :selected] %]))))


(zs/reg-event-fx
  ::filter-by-attr
  (fn [{:keys [db]} [_ k v ttype]]
    (let [fattrs (-> db :view :trace :list :filter-attrs)
          fattrs (if (= ttype (:ttype fattrs)) fattrs {:ttype ttype})
          fattrs (if (contains? fattrs k) (dissoc fattrs k) (assoc fattrs k v))]
      {:db       (assoc-in db [:view :trace :list :filter-attrs] fattrs),
       :dispatch [:zico.views.mon-trace-list/refresh-list]})))


(def FILTER-ATTRS (zs/subscribe [:get [:view :trace :list :filter-attrs]]))


(defn render-attrs [attrs ttype]                            ; TODO ttype -> render-filter-buttons-fn
  "Renders method call attributes (if any)"
  (let [filter-attrs @FILTER-ATTRS]
    [:div.trace-attrs
     (for [[n l k v] (map cons (range) (zu/map-to-seq attrs))]
       ^{:key n}
       [:div.a {:style {:margin-left (str l "em")}}
        [:div.k k]
        [:div.v v]
        (when ttype
          (if (contains? filter-attrs k)
            [:div.i (zw/svg-button
                      :awe :cancel :red "Clear filter ..."
                      [:do
                       [:dissoc [:view :trace :list :filter-attrs] k]
                       [:dissoc [:view :trace :list] :selected]
                       [:zico.views.mon-trace-list/refresh-list]])]
            [:div.i (zw/svg-button
                      :awe :filter :blue "Filter by ..."
                      [:do
                       [:dissoc [:view :trace :list] :selected]
                       [::filter-by-attr k v ttype]])]))
        ; TODO z poniższego zrobić tylko link do detali trace'ów;
        (when (= k "DTRACE_OUT")
          [:div.i
           (zw/svg-button
             :awe :link-ext :blue "Go to target trace..."
             [:event/push-dispatch TRACE_HISTORY [:zico.views.mon-trace-tree/display-tree v]])])
        ])]))


(defn render-exception [exception full]
  [:div.trace-record-exception
   [:div.cls
    [:div.lb "Exception:"]
    [:div.cl (:class exception)]]
   [:div.msg (str (:msg exception))]
   [:div.stk
    (for [[{:keys [class method file line] :as e} i] (map vector (:stack exception) (range))]
      ^{:key i} [:div.si
                 [:div.c (str class "." method)]
                 [:div.f (str "(" file ":" line ")")]])]])

(def FILTER-STATE (zs/subscribe [:get [:view :trace :list :filter]]))

(defn render-trace-list-detail-fn [& {:keys [dtrace-links attr-links]}]
  (fn [{:keys [uuid dtrace-uuid tstamp descr duration recs calls errs host ttype app env]
        {{:keys [package method class result args]} :method :as detail} :detail :as t}]
    ^{:key uuid}
    [:div.det
     {:data-trace-uuid uuid}
     [:div.flex-on-medium-or-more
      [:div tstamp]
      (let [{:keys [glyph name] :as x} (get @CFG-TTYPES ttype),
            [_ f g] (re-matches #"(.+)/(.+)" glyph)]
        [:div.flex
         [:div.i.lpad.rpad (zw/svg-icon (if f (keyword f) :awe) (if g (keyword g) :paw) :text)]
         [:div.ellipsis name]])
      (let [name (get-in @CFG-APPS [app :name])]
        [:div.flex
         [:div.i.lpad.rpad (zw/svg-icon :awe :cubes :yellow)]
         [:div.ellipsis name]])
      (let [name (get-in @CFG-ENVS [env :name])]
        [:div.flex
         [:div.i.lpad.rpad (zw/svg-icon :awe :sitemap :green)]
         [:div.ellipsis name]])]
     [:div.flex
      [:div.lpad.rpad (zw/svg-icon :awe :desktop :text)]
      [:div.ellipsis (str (:name (@CFG-HOSTS host)) "   (" host ")")]
      [:div.i
       (if (-> @FILTER-STATE :host :selected)
         (zw/svg-button
           :awe :cancel :red "Remove filter ..."
           [:do
            [:dissoc [:view :trace :list] :selected]
            [:dissoc [:view :trace :list :filter :host] :selected]
            [:zico.views.mon-trace-list/refresh-list]])
         (zw/svg-button
           :awe :filter :blue "Filter by ..."
           [:do
            [:dissoc [:view :trace :list] :selected]
            [:set [:view :trace :list :filter :host :selected] host]
            [:zico.views.mon-trace-list/refresh-list]]))]]
     [:div.c-light.bold.wrapping descr]
     [:div.c-darker.ellipsis result]
     [:div.c-light.ellipsis (str method args)]
     [:div.c-darker.ellipsis.text-rtl package "." class]
     (cond
       (nil? detail) [:div.wait "Wait..."]
       (nil? (:attrs detail)) [:div.wait "No attributes here."]
       :else (render-attrs (:attrs detail) attr-links))
     (when (:exception detail)
       (render-exception (:exception detail) true))
     [:div.btns
      (zw/svg-icon :awe :flash :yellow) [:div.lbl.small-or-more "Calls:"] [:div.val (str calls)]
      (zw/svg-icon :awe :inbox :green) [:div.lbl.small-or-more "Recs:"] [:div.val (str recs)]
      (zw/svg-icon :awe :bug :red) [:div.lbl.small-or-more "Errors:"] [:div.val (str errs)]
      (zw/svg-icon :awe :clock :blue) [:div.lbl.small-or-more "Time:"] [:div.val (zu/secs-to-str duration)]
      [:div.flexible.flex]                                  ; TODO display trace type
      (zw/svg-button
        :awe :chart-bar :text "Method call stats"
        [:event/push-dispatch TRACE_HISTORY [:zico.views.mon-trace-stats/display-stats uuid]])
      (when (and dtrace-links dtrace-uuid)           ; TODO use explicit flag, not dtrace-level check
        (zw/svg-button
          :ent :flow-cascade :blue "Distributed trace"
          [:event/push-dispatch TRACE_HISTORY [:zico.views.mon-trace-dist/dtrace-tree uuid]]))
      (zw/svg-button
        :awe :right-big :blue "View trace details"
        [:event/push-dispatch TRACE_HISTORY [:zico.views.mon-trace-tree/display-tree uuid]])]]))


(def SUPPRESS-DETAILS (zs/subscribe [:get [:view :trace :list :suppress]]))

(defn render-trace-list-item-fn [& {:keys [dtrace-links]}]
  (fn [{:keys [uuid ttype tstamp descr dtrace-uuid dtrace-level duration recs calls errs flags] :as t}]
    (let [[_ t] (cs/split tstamp #"T") [t _] (cs/split t #"\.")]
      ^{:key uuid}
      [:div.itm
       {:data-trace-uuid uuid}
       [:div.seg
        [:div.ct t]
        [:div.flexible]
        [:div.seg
         (let [{:keys [glyph name] :as x} (get @CFG-TTYPES ttype),
               [_ f g] (re-matches #"(.+)/(.+)" glyph)]
           (zw/svg-icon (if f (keyword f) :awe) (if g (keyword g) :paw) :text))]
        [:div.svg-icon.btn-details.small-or-less.clickable " "]]
       [:div.seg.flexible
        {:style {:padding-left (str (* 16 (or dtrace-level 0)) "px")}}
        [(if (:err flags) :div.c2.c-red :div.c2.c-text) descr]]
       [:div.seg
        (zw/svg-icon :awe :clock :blue) (zu/secs-to-str duration)
        (when-not @SUPPRESS-DETAILS
          [:div.flex
           (zw/svg-icon :awe :flash :yellow) (str calls)
           (zw/svg-icon :awe :inbox :green) (str recs)
           (zw/svg-icon :awe :bug :red) (str errs)])
        (cond
          (not dtrace-links)
          (zw/svg-icon
            :ent :flow-cascade  :none)
          (some? dtrace-uuid)
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


