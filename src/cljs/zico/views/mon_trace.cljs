(ns zico.views.mon-trace
  (:require
    [reagent.ratom :as ra]
    [clojure.string :as cs]
    [cljs-time.format :as ctf]
    [zico.util :as zu]
    [zico.state :as zs]
    [zico.popups :as zp]
    [zico.widgets :as zw]
    [zico.views.common :as zv]))


(def DURATIONS
  [["0" "<clear filter>"  nil ]
   ["1s"  "1 second"    1     ]
   ["5s"  "5 seconds"   5     ]
   ["15s" "15 seconds"  15    ]
   ["1m"  "1 minute"    60    ]
   ["5m"  "5 minutes"   300   ]
   ["15m" "15 minutes"  900   ]
   ["1h"  "1 hour"      3600  ]
   ["4h"  "4 hours"     14400 ]
   ["1d"  "1 day"       86400 ]])


(def DURATION-FILTER-ITEMS
  (for [[k l t] DURATIONS]
    {:key k, :text l, :icon [:awe (if t :clock :block) (if t :text :red)],
     :on-click [::filter-list [:view :trace :list :filter :dur :selected] t]}))


; Register all needed subscriptions
(zs/register-sub
  :data/trace-type-list
  (zs/data-list-sfn :trace :type :name))


(zs/register-sub
  :data/trace-list-list
  (fn [db _]
    (let [data (ra/reaction (get-in @db [:data :trace :list]))
          order (ra/reaction (get-in @db [:view :trace :list :sort :dur]))]
      (ra/reaction
        (reverse (sort-by (if @order :duration :tstamp) (vals @data))))
      )))


(zs/register-sub
  :data/trace-tree-list
  (fn [db [_]]
    (ra/reaction (get-in @db [:data :trace :tree]))))

(def CFG-TTYPES (zs/subscribe [:get [:data :cfg :ttype]]))

(def TTYPE-MODES {:client :blue, :server :green})

(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))

(defn datetime-to-param [d]
  (ctf/unparse PARAM-FORMATTER d))


(defn format-filter-args [db offset]
  (let [vroot (-> db :view :trace :list)
        text (-> vroot :search :text)
        argv (for [k [:ttype :app :env :dur :err :tstart :tstop]
                   :let [v (-> vroot :filter k :selected)]
                   :when v
                   :let [v (if (#{:tstart :tstop} k) (ctf/unparse PARAM-FORMATTER v) v)]]
               (str (name k) "=" v))
        argv (concat argv [(str "offset=" offset) (str "order=" (if (-> vroot :sort :dur) "d" "t"))])
        argv (if (empty? text) argv (concat argv [(str "text=" text)]))]
    (str "../../../data/trace/list"
      (if (empty? argv) "" (str "?" (reduce #(str %1 "&" %2) argv))))))


(zs/reg-event-fx
  ::filter-list
  (fn [{:keys [db]} [_ path v]]
    {:db (assoc-in db path v)
     :dispatch [::refresh-list]}))


(zs/reg-event-fx
  ::scroll-list
  (fn [{:keys [db]} [_ {:keys [scroll-top scroll-height offset-height]}]]
    (let [diff (- scroll-height offset-height scroll-top)
          {:keys [loading]} (-> db :view :trace :list)]
      (cond
        loading {:db db}
        (> diff 100) {:db db}
        :else
        {:db (assoc-in db [:view :trace :list :loading] true)
         :dispatch [::extend-list]}))))


(zs/reg-event-fx
  ::extend-list
  (fn [{:keys [db]} _]
    {:db db
     :dispatch
     [:rest/get (format-filter-args db (count (-> db :data :trace :list)))
      [:data :trace :list] :map-by :uuid, :merge-by merge
      :on-success [::extend-list-notification]
      ]}))


(zs/reg-event-db
  ::extend-list-notification
  (fn [db [_ vs]]
    (if-not (empty? vs)
      (assoc-in db [:view :trace :list :loading] nil)
      db)))


(zs/reg-event-fx
  ::refresh-list
  (fn [{:keys [db]} [_ path v]]
    {:db db
     :dispatch
     [:rest/get (format-filter-args db 0)
      [:data :trace :list] :map-by :uuid
      :on-success [::extend-list-notification]]}))


(zs/reg-event-fx
  ::filter-by-attr
  (fn [{:keys [db]} [_ k v]]
    (let [q (str k " == \"" v "\"")]
      {:db db, :dispatch-n [[:form/set-value [:view :trace :list :search] :nil q] [::refresh-list]]})))


(defn render-attrs [attrs]
  "Renders method call attributes (if any)"
  [:div.trace-attrs
   (for [[n l k v] (map cons (range) (zu/map-to-seq attrs))]
     ^{:key n}
     [:div.a {:style {:margin-left (str l "em")}}
      [:div.k k]
      [:div.v v]
      [:div.i (zw/svg-button :awe :filter :blue "Filter by." [::filter-by-attr k v])]])])

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


(defn render-trace-list-detail [{:keys [uuid ttype tstamp descr duration recs calls errs ]
                                 {{:keys [package method class result args]} :method :as detail} :detail
                                 :as t}]
  ^{:key uuid}
  [:div.det
   {:data-trace-uuid uuid}
   [:div
    [:div tstamp]
    ;[:div uuid]
    ; TODO trace type description does not work (currently)
    ;(when-let [{:keys [family glyph mode name comment] :as tt} (get @CFG-TTYPES ttype)]
    ;  [:div.tt
    ;   [:div.i (zw/svg-icon (or family :awe) (or glyph :paw) (TTYPE-MODES mode :black))]
    ;   [:div.n name]
    ;   [:div.c.hide-s comment]])
    ]
   [:div.c-light.bold descr]
   [:div.c-darker result]
   [:div.c-light.ellipsis (str method args)]
   [:div.c-darker.ellipsis.text-rtl package "." class]
   (cond
     (nil? detail) [:div.wait "Wait..."]
     (nil? (:attrs detail)) [:div.wait "No attributes here."]
     :else (render-attrs (:attrs detail)))
   (when (:exception detail)
     (render-exception (:exception detail) true))
   [:div.btns
    (zw/svg-icon :awe :flash :yellow) [:div.lbl.small-or-more "Calls:"] [:div.val (str calls)]
    (zw/svg-icon :awe :inbox :green) [:div.lbl.small-or-more "Recs:"] [:div.val (str recs)]
    (zw/svg-icon :awe :bug :red) [:div.lbl.small-or-more "Errors:"] [:div.val (str errs)]
    (zw/svg-icon :awe :clock :blue) [:div.lbl.small-or-more "Time:"] [:div.val (zu/ticks-to-str duration)]
    [:div.flexible.flex]
    ;(zw/svg-button
    ;  :awe :floppy :light "Save this trace"
    ;  [:alert "TODO nieczynne: save trace" uuid])
    ;(zw/svg-button
    ;  :awe :pin :light "Pin this trace"
    ;  [:alert "TODO nieczynne: pin trace" uuid])
    ;(zw/svg-button
    ;  :awe :pencil :light "Annotate this trace"
    ;  [:alert "TODO nieczynne: annotate trace" uuid])
    (zw/svg-button
      :awe :right-big :blue "View trace details"
      [::display-tree uuid])]])

(def SUPPRESS-DETAILS (zs/subscribe [:get [:view :trace :list :suppress]]))

(defn render-trace-list-item [{:keys [uuid ttype tstamp descr duration recs calls errs flags] :as t}]
  (let [[_ t] (cs/split tstamp #"T") [t _] (cs/split t #"\.")]
    ^{:key uuid}
    [:div.itm
     {:data-trace-uuid uuid}
     [:div.seg
      [:div.ct t]
      [:div.flexible]
      [:div.seg
       (when-not @SUPPRESS-DETAILS
         [:div.flex
          (zw/svg-icon :awe :flash :yellow) (str calls)
          (zw/svg-icon :awe :inbox :green) (str recs)
          (zw/svg-icon :awe :bug :red) (str errs)])
       (zw/svg-icon :awe :clock :blue) (zu/ticks-to-str duration)]
      (let [{:keys [family glyph mode]} (get @CFG-TTYPES ttype)]
        (zw/svg-icon (or family :awe) (or glyph :paw) (TTYPE-MODES mode :text)))
      [:div.svg-icon.btn-details.small-or-less.clickable " "]]
     [:div.seg.flexible [(if (:err flags) :div.c2.c-red :div.c2.c-text) descr]
      (zw/svg-icon :awe :right-big :blue, :class " clickable btn-details")]]))


(defn filtered-items [path data icon]
  (let [selected (zs/subscribe [:get path])]
    (ra/reaction
      (doall
        (cons
          {:key "nil", :text "<clear>", :icon [:awe :cancel :red],
           :on-click [:do [:set path nil] [::refresh-list]]}
          (for [{:keys [uuid name]} (sort-by :name (vals (zu/deref? data)))]
            {:key      uuid, :text name, :icon icon,
             :on-click [:do [:set path uuid] [::refresh-list]]
             :state    (if (= uuid @selected) :selected :normal)
             }))))))


(def TRACE-TYPE-ITEMS
  (filtered-items
    [:view :trace :list :filter :ttype :selected]
    (zs/subscribe [:get [:data :cfg :ttype]])
    [:awe :list-alt :text]))

(def APP-ITEMS
  (filtered-items
    [:view :trace :list :filter :app :selected]
    (zs/subscribe [:get [:data :cfg :app]])
    [:awe :cubes :text]))

(def ENV-ITEMS
  (filtered-items
    [:view :trace :list :filter :env :selected]
    (zs/subscribe [:get [:data :cfg :env]])
    [:awe :sitemap :text]))


(defn toolbar-right []
  (let [view-state (zs/subscribe [:get [:view :trace :list]])]
    (fn []
      (let [view-state @view-state]
        [:div.flexible.flex
         (zw/svg-button :awe :attention-circled :red "Errors only"
           [::filter-list [:view :trace :list :filter :err :selected]
            (not (get-in view-state [:filter :err :selected]))]
           :opaque (get-in view-state [:filter :err :selected]))
         (zw/svg-button :awe :calendar-plus-o :light "From"
           [:popup/open :calendar, :position :top-right, :on-click-kw ::filter-list,
            :path [:view :trace :list :filter :tstart]]
           :opaque (get-in view-state [:filter :tstart :selected]))
         (zw/svg-button :awe :calendar-minus-o :light "To"
           [:popup/open :calendar, :position :top-right, :on-click-kw ::filter-list,
            :path [:view :trace :list :filter :tstop]]
           :opaque (get-in view-state [:filter :tstop :selected]))
         (zw/svg-button
           :awe :clock :light "Minimum duration"
           [:popup/open :menu, :position :top-right, :items DURATION-FILTER-ITEMS
            :path [:view :trace :list :filter :dur :selected]]
           [:toggle [:view :trace :list :filter :dur :open?]]
           :opaque (get-in view-state [:filter :dur :selected]))
         (zw/svg-button :awe :list-alt :light "Trace type"
           [:popup/open :menu, :position :top-right, :items TRACE-TYPE-ITEMS]
           :opaque (get-in view-state [:filter :ttype :selected]))
         (zw/svg-button :awe :cubes :light "Application"
           [:popup/open :menu, :position :top-right, :items APP-ITEMS]
           :opaque (get-in view-state [:filter :app :selected]))
         ; TODO dodac nagłówki do filter popupów i zobaczyć jak się zachowają i czy będzie działać scrolling;
         (zw/svg-button :awe :sitemap :light "Environment"
           [:popup/open :menu, :position :top-right, :items ENV-ITEMS]
           :opaque (get-in view-state [:filter :env :selected]))
         (zw/svg-button :awe :cancel :red "Clear filters."
           [::filter-list [:view :trace :list :filter] {}]
           :opaque (some? []))]))))


(defn toolbar-left []
  (let [view-state (zs/subscribe [:get [:view :trace :list]])]
    (fn []
      (let [{:keys [sort suppress]} @view-state]
        [:div.flexible.flex
         (zw/svg-button
           :awe :sort-alt-down :light "Sort by duration"
           [::filter-list [:view :trace :list :sort :dur] (not (:dur sort))]
           :opaque (:dur sort))
         (zw/svg-button
           :awe :eye-off :light "Suppress details"
           [:toggle [:view :trace :list :suppress]]
           :opaque suppress)]))))


(defn trace-list-click-handler [e]
  (zs/traverse-and-handle
    (.-target e) "data-trace-uuid" "zorka-traces"
    :btn-details #(zs/dispatch [::display-tree %])
    :itm (fn [v]
           (zs/dispatch [:toggle [:view :trace :list :selected] v])
           (zs/dispatch [:rest/get (str "../../../data/trace/" v "/detail")
                         [:data :trace :list v :detail]]))
    :det #(zs/dispatch [:toggle [:view :trace :list :selected] %])))


(defn trace-list []
  "Trace seach/listing panel: [:view :trace :list]"
  (zs/dispatch [:once :data/refresh :cfg :app])
  (zs/dispatch [:once :data/refresh :cfg :env])
  (zs/dispatch [:once :data/refresh :cfg :ttype])
  (zs/dispatch [::refresh-list])
  (zv/render-screen
    :toolbar [zv/list-screen-toolbar :trace :list
              {:title "Traces", :add-left [toolbar-left], :add-right [toolbar-right]
               :sort-ctls {}, :on-refresh [::refresh-list]}]
    :central [zv/list-interior [:trace :list] render-trace-list-item render-trace-list-detail
              :id "zorka-traces", :on-scroll [::scroll-list], :on-click trace-list-click-handler]))


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
      [:rest/get (str "../../../data/trace/" uuid "/tree")
       [:data :trace :tree] :proc-by ::display-tree]]}))

(defn render-trace-tree-detail [{:keys [pos attrs duration exception]
                                 {:keys [result package class method args]} :method}]
  ^{:key pos}
  [:div.det {:data-trace-pos pos}
   [:div.method
    [:div.c-darker.flexible result]
    [:div.ti (zw/svg-icon :awe :clock :blue) [:div.flexible] (zu/ticks-to-str duration)]]
   [:div.c-light.ellipsis (str method args)]
   [:div.c-darker.text-rtl.ellipsis package "." class]
   (when attrs (render-attrs attrs))
   (when exception (render-exception exception true))])


(defn render-trace-tree-item [{:keys [pos level duration attrs exception]
                               {:keys [result package class method args]} :method}]
  ^{:key pos}
  [:div.itm.method {:data-trace-pos pos}
   [:div.t (zu/ticks-to-str duration)]
   [(cond attrs :div.mc.c-blue exception :div.mc.c-red :else :div.mc.c-text)
    {:style {:margin-left (str (* level 10))}}
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
         (zw/svg-button :awe :left-big :blue "To trace list"
           [:to-screen "mon/trace/list"])]))))


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

