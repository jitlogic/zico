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
  [["0" "clear"  nil ]
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
     :on-click [::filter-list [:view :trace :list :filter :min-duration :selected] t]}))


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
(def CFG-HOSTS (zs/subscribe [:get [:data :cfg :host]]))
(def CFG-ENVS (zs/subscribe [:get [:data :cfg :env]]))
(def CFG-APPS (zs/subscribe [:get [:data :cfg :app]]))

(def TTYPE-MODES {:client :blue, :server :green})

(def PARAM-FORMATTER (ctf/formatter "yyyyMMdd'T'HHmmssZ"))

(defn make-filter [db offset]
  (let [vroot (-> db :view :trace :list)
        text (-> vroot :search :text)
        qmi (into
              {:type :qmi}
              (for [k [:ttype :app :env :min-duration :host]
                    :let [v (-> vroot :filter k :selected)]
                    :when v]
                {k v}))
        qmi (into
              qmi
              (for [k [:tstart :tstop]
                    :let [v (-> vroot :filter k :selected)]
                    :when v]
                {k (ctf/unparse PARAM-FORMATTER v)}))
        tft (if-not (empty? text) {:type :text, :text text})
        all (for [[k v] (:filter-attrs vroot) :when (string? k)] {:type :kv, :key k, :val v})
        all (for [f (into [tft] all) :when (some? f)] f)
        q (cond
            (> (count all) 1) {:type :and, :args (vec all)}
            (= (count all) 1) (first all)
            :else nil)
        deep-search (= true (-> db :view :trace :list :deep-search))]
    (merge {:limit 50, :offset offset, :deep-search deep-search, :node q, :qmi qmi})))


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
         [:rest/post "../../../data/trace/search"
          (make-filter db (count (get-in db [:data :trace :list])))
          :on-success [::handle-trace-search-result false]]}))


(zs/reg-event-db
  ::extend-list-notification
  (fn [db [_ vs]]
    (if-not (empty? vs)
      (assoc-in db [:view :trace :list :loading] nil)
      db)))


(zs/reg-event-fx
  ::refresh-list
  (fn [{:keys [db]} _]
    {:db db
     :dispatch
     [:rest/post "../../../data/trace/search" (make-filter db 0)
      :on-success [::handle-trace-search-result true]]}))


(zs/reg-event-fx
  ::dtrace-tree
  (fn [{:keys [db]} [_ uuid]]
    (let [dtrace-uuid (get-in db [:data :trace :list uuid :dtrace-uuid])]
      {:db (assoc-in db [:data :dtrace :tree] [])
       :dispatch-n
           [[:to-screen "mon/dtrace/tree"]
            [:rest/post "../../../data/trace/search"
             {:limit 1000, :offset 0 :qmi {:dtrace-uuid dtrace-uuid}}
             :on-success [:set [:data :dtrace :tree]], :map-by :uuid]]})))


(zs/reg-event-fx
  ::handle-trace-search-result
  (fn [{:keys [db]} [_ clean data]]
    (let [d0 (if clean {} (get-in db [:data :trace :list] {}))
          sel (get-in db [:view :trace :list :selected])
          uuids (into #{} (map :uuid data))
          evt (if (and sel (contains? uuids sel))
                [:rest/get (str "../../../data/trace/" sel "/detail") [:data :trace :list sel :detail]]
                [:nop])]
      {:db       (assoc-in db [:data :trace :list] (into d0 (for [d data] {(:uuid d) d})))
       :dispatch [:do evt [::extend-list-notification data]]})))


(zs/reg-event-fx
  ::filter-by-attr
  (fn [{:keys [db]} [_ k v ttype]]
    (let [fattrs (-> db :view :trace :list :filter-attrs)
          fattrs (if (= ttype (:ttype fattrs)) fattrs {:ttype ttype})
          fattrs (if (contains? fattrs k) (dissoc fattrs k) (assoc fattrs k v))]
      {:db       (assoc-in db [:view :trace :list :filter-attrs] fattrs),
       :dispatch [::refresh-list]})))


(def FILTER-ATTRS (zs/subscribe [:get [:view :trace :list :filter-attrs]]))

(def TRACE_HISTORY [:view :trace :history])

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
                       [::refresh-list]])]
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
             [:event/push-dispatch TRACE_HISTORY [::display-tree v]])])
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
     [:div.flex
      [:div tstamp]
      (let [{:keys [glyph name] :as x} (get @CFG-TTYPES ttype),
            [_ f g] (re-matches #"(.+)/(.+)" glyph)]
        [:div.flex
         [:div.i (zw/svg-icon (if f (keyword f) :awe) (if g (keyword g) :paw) :text)]
         [:div.ellipsis name]])
      (let [name (get-in @CFG-APPS [app :name])]
        [:div.flex
         [:div.i (zw/svg-icon :awe :cubes :text)]
         [:div.ellipsis name]])
      (let [name (get-in @CFG-ENVS [env :name])]
        [:div.flex
         [:div.i (zw/svg-icon :awe :sitemap :text)]
         [:div.ellipsis name]])]

     [:div.flex
      [:div.ellipsis (str "Host: " (:name (@CFG-HOSTS host)) "   (" host ")")]
      [:div.i
       (if (-> @FILTER-STATE :host :selected)
         (zw/svg-button
           :awe :cancel :red "Remove filter ..."
           [:do
            [:dissoc [:view :trace :list] :selected]
            [:dissoc [:view :trace :list :filter :host] :selected]
            [::refresh-list]])
         (zw/svg-button
           :awe :filter :blue "Filter by ..."
           [:do
            [:dissoc [:view :trace :list] :selected]
            [:set [:view :trace :list :filter :host :selected] host]
            [::refresh-list]]))]]
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
      (when (and dtrace-links dtrace-uuid)           ; TODO use explicit flag, not dtrace-level check
        (zw/svg-button
          :ent :flow-cascade :blue "Distributed trace"
          [:event/push-dispatch TRACE_HISTORY [::dtrace-tree uuid]]))
      (zw/svg-button
        :awe :right-big :blue "View trace details"
        [:event/push-dispatch TRACE_HISTORY [::display-tree uuid]])]]))


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


(defn filtered-items [path data icon]
  (let [selected (zs/subscribe [:get path])]
    (ra/reaction
      (doall
        (cons
          {:key "nil", :text "clear filter", :icon [:awe :cancel :red],
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


; TODO this is too complicated; filters need to have its own unified data structure working on single loop
(defn clear-filter-items []
  (let [cfg (zs/subscribe [:get [:data :cfg]])
        filter (zs/subscribe [:get [:view :trace :list :filter]])
        search (zs/subscribe [:get [:view :trace :list :search]])
        fattrs (zs/subscribe [:get [:view :trace :list :filter-attrs]])]
    (ra/reaction
      (let [filter @filter, search @search, fattrs @fattrs,
            f1 (for [[k lbl ic] [[:app "App: " :cubes] [:env "Env: " :sitemap] [:ttype "Type: " :list-alt] [:host "Host: " :sitemap]]
                     :let [uuid (get-in filter [k :selected])] :when uuid]
                 {:key      (str k), :text (str lbl (get-in @cfg [k uuid :name])), :icon [:awe ic :red],
                  :on-click [:do [:set [:view :trace :list :filter k] {}] [::refresh-list]]})
            f2 (when-not (empty? (:text search))
                 [{:key      ":text", :text (str "Search: " (zu/ellipsis (:text search) 32)), :icon [:awe :search :red],
                   :on-click [:do [:set [:view :trace :list :search] {}] [::refresh-list]]}])
            f3 (for [[k _] fattrs :when (string? k)]
                 {:key      (str "ATTR-" k), :text (zu/ellipsis (str "Attr: " k) 32), :icon [:awe :filter :red],
                  :on-click [:do [:dissoc [:view :trace :list :filter-attrs] k] [::refresh-list]]})]
        (doall
          (concat
            [{:key "all", :text "clear filters", :icon [:awe :cancel :red]
              :on-click [:do
                         [:set [:view :trace :list :filter] {}]
                         [:set [:view :trace :list :filter-attrs] {}]
                         [:set [:view :trace :list :search] {}]
                         [::refresh-list]]}]
            f1 f2 f3)))
      )))

(def CLEAR-FILTER-ITEMS (clear-filter-items))


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
            :path [:view :trace :list :filter :min-duration :selected]]
           :opaque (get-in view-state [:filter :min-duration :selected]))
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
                        [:popup/open :menu :position :top-right :items CLEAR-FILTER-ITEMS]
                        :opaque (> (count @CLEAR-FILTER-ITEMS) 1))
         ]))))


(defn toolbar-left []
  (let [view-state (zs/subscribe [:get [:view :trace :list]])]
    (fn []
      (let [{:keys [sort suppress deep-search]} @view-state]
        [:div.flexible.flex
         (zw/svg-button
           :awe :sort-alt-down :light "Sort by duration"
           [::filter-list [:view :trace :list :sort :dur] (not (:dur sort))]
           :opaque (:dur sort))
         (zw/svg-button
           :awe :binoculars :light "Deep search"
           [:do [:toggle [:view :trace :list :deep-search]] [::refresh-list]]
           :opaque deep-search)
         (zw/svg-button
           :awe :eye-off :light "Suppress details"
           [:toggle [:view :trace :list :suppress]]
           :opaque suppress)]))))


(defn trace-list-click-handler-fn [sect sub]
  (fn [e]
    (zs/traverse-and-handle
      (.-target e) "data-trace-uuid" "zorka-traces"
      :btn-details #(zs/dispatch [:event/push-dispatch TRACE_HISTORY [::display-tree %]])
      :btn-dtrace #(zs/dispatch [:event/push-dispatch TRACE_HISTORY [::dtrace-tree %]])
      :itm #(zs/dispatch [:do [:toggle [:view sect sub :selected] %]
                          [:rest/get (str "../../../data/trace/" % "/detail")
                           [:data sect sub % :detail]]])
      :det #(zs/dispatch [:toggle [:view sect sub :selected] %]))))


(defn trace-list []
  "Trace seach/listing panel: [:view :trace :list]"
  (zs/dispatch [:once :data/refresh :cfg :app])
  (zs/dispatch [:once :data/refresh :cfg :env])
  (zs/dispatch [:once :data/refresh :cfg :ttype])
  (zs/dispatch [:once :data/refresh :cfg :host])
  (zs/dispatch [::refresh-list])
  (zv/render-screen
    :toolbar [zv/list-screen-toolbar :trace :list
              {:title "Traces", :add-left [toolbar-left], :add-right [toolbar-right]
               :sort-ctls {}, :on-refresh [::refresh-list]}]
    :central [zv/list-interior [:trace :list]
              (render-trace-list-item-fn :dtrace-links true)
              (render-trace-list-detail-fn :dtrace-links true, :attr-links true)
              :id "zorka-traces", :on-scroll [::scroll-list],
              :on-click (trace-list-click-handler-fn :trace :list)]))


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
   (when attrs (render-attrs attrs nil))
   (when exception (render-exception exception true))])


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
           [:event/pop-dispatch TRACE_HISTORY [:to-screen "mon/trace/list"]])]))))


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
     :awe :left-big :blue "To trace list"
     [:event/pop-dispatch TRACE_HISTORY [:to-screen "mon/trace/list"]])
   (zw/svg-button
     :awe :eye-off :light "Suppress details"
     [:toggle [:view :trace :list :suppress]]
     :opaque SUPPRESS-DETAILS)
   (zw/svg-button
     :awe :link-ext :light "Display output traces."
     [:toggle [:view :dtrace :tree :dtrace-out]]
     :opaque DISPLAY-DTRACE-OUT)
   ])


(defn dtrace-tree []
  "Displays distributed trace panel [:view :trace :dtrace]"
  (zv/render-screen
    :hide-menu-btn true
    :toolbar [zv/list-screen-toolbar :dtrace :tree
              {:title     "Distributed tracing",
               :sort-ctls {}
               :flags     #{:no-refresh, :no-bookmark}
               :add-left  [dtrace-toolbar-left]
               ;:add-right [:div " "]
               }]
    :central [zv/list-interior [:dtrace :tree]
              (render-trace-list-item-fn :dtrace-links false)
              (render-trace-list-detail-fn :attr-links false, :dtrace-links false)
              :id-attr :uuid, :id "zorka-dist", :class "trace-list-list",
              :on-click (trace-list-click-handler-fn :dtrace :tree)]
    ))

