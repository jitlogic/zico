(ns zico.views.mon-trace
  (:require
    [reagent.ratom :as ra]
    [clojure.string :as cs]
    [zico.util :as zu]
    [zico.state :as zs]
    [zico.widgets :as zw]
    [zico.views.common :as zv]))


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


(def CFG-TTYPES (zs/subscribe [:get [:data :cfg :ttype]]))
(def CFG-HOSTS (zs/subscribe [:get [:data :cfg :host]]))
(def CFG-ENVS (zs/subscribe [:get [:data :cfg :env]]))
(def CFG-APPS (zs/subscribe [:get [:data :cfg :app]]))
(def CFG-TRACES (zs/subscribe [:get [:data :trace :list]]))

(defn trace-list-click-handler-fn [sect sub]
  (fn [e]
    (zs/traverse-and-handle
      (.-target e) "data-trace-uuid" "zorka-traces"
      :btn-details #(zs/dispatch [:to-screen "mon/trace/tree" {:uuid %}])
      :btn-dtrace #(zs/dispatch [:to-screen "mon/trace/dtree" {:dtrace-uuid (get-in @CFG-TRACES [% :dtrace-uuid])}])
      :itm #(zs/dispatch [:do [:toggle [:view sect sub :selected] %]
                          [:xhr/get (str "../../../data/trace/" % "/detail")
                           [:data sect sub % :detail] nil
                           :on-error zv/DEFAULT-SERVER-ERROR]])
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
     (for [[n l k v] (map cons (range) (zu/map-to-seq attrs))]
       ^{:key n}
       [:div.a {:style {:margin-left (str l "em")}}
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

(defn ttype-get [ttype]
  (let [{:keys [glyph name uuid]} (get @CFG-TTYPES ttype {:glyph "awe/paw#text", :name "<unknown>"})
             [_ f g c] (re-matches #"(.+)/([^#]+)#?(.*)?" glyph)]
    {:family (keyword f), :glyph (keyword g), :name name, :uuid uuid, :color (keyword c)}))

(defn render-trace-list-detail-fn [enable-filters dtrace-links]
  (fn [{:keys [uuid dtrace-uuid tstamp descr duration recs calls errs host ttype app env]
        {{:keys [package method class result args]} :method :as detail} :detail :as t}]
    ^{:key uuid}
    [:div.det
     {:data-trace-uuid uuid}
     [:div.flex-on-medium-or-more
      [:div tstamp]
      (let [{:keys [family glyph color name uuid]} (ttype-get ttype)]
        [:div.flex
         [:div.i.lpad.rpad
          (if (and enable-filters (some? uuid))
            (zw/svg-button
              family glyph color (str "Show only " name " traces.")
              [:do [:set [:view :trace :list :filter :ttype :selected] uuid]
               [:zico.views.mon-trace-list/refresh-list true]])
            (zw/svg-icon family glyph color :opaque false))]
         [:div.ellipsis name]])
      (let [{:keys [name uuid]} (get @CFG-APPS app {:name "<unknown>"})]
        [:div.flex
         [:div.i.lpad.rpad
          (if (and enable-filters uuid)
            (zw/svg-button
              :awe :cubes :yellow (str "Show only " name " application.")
              [:do [:set [:view :trace :list :filter :app :selected] uuid]
               [:zico.views.mon-trace-list/refresh-list true]])
            (zw/svg-icon :awe :cubes :yellow :opaque false))]
         [:div.ellipsis name]])
      (let [{:keys [name uuid]} (get @CFG-ENVS env {:name "<unknown>"})]
        [:div.flex
         [:div.i.lpad.rpad
          (if (and enable-filters uuid)
            (zw/svg-button
              :awe :sitemap :green (str "Show only " name " environment.")
              [:do [:set [:view :trace :list :filter :env :selected] uuid]
               [:zico.views.mon-trace-list/refresh-list true]])
            (zw/svg-icon :awe :sitemap :green :opaque false))]
         [:div.ellipsis name]])]
     (let [{:keys [name uuid]} (get @CFG-HOSTS host {:name "<unknown host>"})]
       [:div.flex
        [:div.lpad.rpad
         (if (and enable-filters uuid)
           (zw/svg-button
             :awe :desktop :blue (str "Show only " name " host.")
             [:do [:set [:view :trace :list :filter :host :selected] uuid]
              [:zico.views.mon-trace-list/refresh-list true]])
           (zw/svg-icon :awe :desktop :text))]
        [:div.ellipsis (str name "   (" uuid ")")]])
     [:div.c-light.bold.wrapping descr]
     [:div.c-darker.ellipsis result]
     [:div.c-light.ellipsis (str method args)]
     [:div.c-darker.ellipsis.text-rtl package "." class]
     (cond
       (nil? detail) [:div.wait "Wait..."]
       (nil? (:attrs detail)) [:div.wait "No attributes here."]
       :else (render-attrs (:attrs detail) enable-filters))
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
        [:to-screen "mon/trace/stats" {:uuid uuid}])
      (when (and dtrace-links dtrace-uuid)           ; TODO use explicit flag, not dtrace-level check
        (zw/svg-button
          :ent :flow-cascade :blue "Distributed trace"
          [:to-screen "mon/trace/dtree" {:dtrace-uuid (get-in @CFG-TRACES [uuid :dtrace-uuid])}]))
      (zw/svg-button
        :awe :right-big :blue "View trace details"
        [:to-screen "mon/trace/tree" {:uuid uuid}]
        )]]))


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
         (let [{:keys [family glyph color]} (ttype-get ttype)]
           (zw/svg-icon family glyph color))]
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


