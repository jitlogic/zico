(ns zico.views.mon-trace-list
  (:require
    [reagent.ratom :as ra]
    [zico.state :as zs]
    [zico.views.common :as zv]
    [zico.views.mon-trace :as zvmt]
    [zico.widgets :as zw]
    [cljs-time.format :as ctf]
    [zico.util :as zu]))


(zs/register-sub
  :data/trace-list-list
  (fn [db _]
    (let [data (ra/reaction (get-in @db [:data :trace :list]))
          order (ra/reaction (get-in @db [:view :trace :list :sort :dur]))]
      (ra/reaction
        (reverse (sort-by (if @order :duration :tstamp) (vals @data))))
      )))


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
         [:xhr/post "../../../data/trace/search" nil
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
         [:xhr/post "../../../data/trace/search" nil (make-filter db 0)
          :on-success [::handle-trace-search-result true]]}))


(zs/reg-event-fx
  ::handle-trace-search-result
  (fn [{:keys [db]} [_ clean data]]
    (let [d0 (if clean {} (get-in db [:data :trace :list] {}))
          sel (get-in db [:view :trace :list :selected])
          uuids (into #{} (map :uuid data))
          evt (if (and sel (contains? uuids sel))
                [:xhr/get (str "../../../data/trace/" sel "/detail") [:data :trace :list sel :detail] nil]
                [:nop])]
      {:db       (assoc-in db [:data :trace :list] (into d0 (for [d data] {(:uuid d) d})))
       :dispatch [:do evt [::extend-list-notification data]]})))


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
              (zvmt/render-trace-list-item-fn :dtrace-links true)
              (zvmt/render-trace-list-detail-fn :dtrace-links true, :attr-links true)
              :id "zorka-traces", :on-scroll [::scroll-list],
              :on-click (zvmt/trace-list-click-handler-fn :trace :list)]))

