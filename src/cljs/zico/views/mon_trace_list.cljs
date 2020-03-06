(ns zico.views.mon-trace-list
  (:require
    [reagent.ratom :as ra]
    [zico.widgets.state :as zs]
    [zico.views.common :as zv]
    [zico.views.mon-trace :as zvmt]
    [zico.widgets.widgets :as zw]
    [cljs-time.format :as ctf]
    [cljs.reader :refer [read-string]]
    [zico.widgets.util :as zu]
    [cljsjs.lz-string]
    [zico.widgets.io :as io]
    [zico.widgets.screen :as zws]))


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
  (let [vroot (-> db :view :trace :list),
        text (-> vroot :search :text)
        min-duration (get-in vroot [:filter :min-duration :selected])
        time (get-in vroot [:filter :time :selected])
        errors-only (get-in vroot [:filter :errors-only :selected])
        attrs (into {} (for [k (keys (-> vroot :filter))
                             :let [v (get-in vroot [:filter k :selected])]
                             :when (and (string? k) (string? v))] {k v}))]
    (merge
      {:limit 100, :offset offset, :fetch-attrs true, :spans-only (not (:deep-search vroot))}
      (when-not (empty? attrs) {:attr-matches attrs})
      (when min-duration {:min-duration (* min-duration 1000000000)})
      (when time {:min-tstamp (ctf/unparse PARAM-FORMATTER time)
                  :max-tstamp (ctf/unparse PARAM-FORMATTER (zw/day-next time))})
      (when errors-only {:errors-only errors-only})
      (when text {:text text})
      )))


(defn parse-filter-query [db [_ q]]
  (if q
    (let [qf (read-string (js/LZString.decompressFromBase64 q))]
      (zu/recursive-merge
        db
        {:view
         {:trace
          {:list
           {:deep-search (not (:spans-only qf))
            :search
            {:text (:text qf)}
            :filter
            (merge
              {:min-duration (when (:min-duration qf) {:selected (/ (:min-duration qf) 1000000000)})
               :time (when (:min-tstamp qf) {:selected (ctf/parse PARAM-FORMATTER (:min-tstamp qf))})
               :errors-only {:selected (:errors-only qf false)}}
              (into {}
                (for [[k v] (:attr-matches qf)]
                  {k {:selected v}})))}}}}))
    db))


(zs/reg-event-fx
  ::filter-list
  (fn [{:keys [db]} [_ path v]]
    {:db (assoc-in db path v)
     :dispatch [::refresh-list true]}))


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
         [:xhr/post (io/api "/trace") nil
          (make-filter db (count (get-in db [:data :trace :list])))
          :on-success [::handle-trace-search-result false]
          :on-error zv/DEFAULT-SERVER-ERROR]}))


(zs/reg-event-db
  ::extend-list-notification
  (fn [db [_ vs]]
    (if-not (empty? vs)
      (assoc-in db [:view :trace :list :loading] nil)
      db)))


(zs/reg-event-db ::parse-filter-query parse-filter-query)


(zs/reg-event-fx
  ::refresh-list
  (fn [{:keys [db]} [_ history-push]]
    (let [flt (make-filter db 0)
          zf (js/LZString.compressToBase64 (pr-str flt))]
      {:db db
       :dispatch-n
           [[:xhr/post (io/api "/trace") nil flt
             :on-success [::handle-trace-search-result true]
             :on-error zv/DEFAULT-SERVER-ERROR]
            (if history-push [:history-replace "mon/trace/list" {:q zf}] [:nop])]})))


(zs/reg-event-fx
  ::handle-trace-search-result
  (fn [{:keys [db]} [_ clean data]]
    (let [data (for [d data] (assoc d :tid (zu/to-tid d)))
          d0 (if clean {} (get-in db [:data :trace :list] {}))]
      {:db       (assoc-in db [:data :trace :list] (into d0 (for [d data] {(:tid d) d})))
       :dispatch [::extend-list-notification data]})))


(def DURATIONS
  [["0" "clear"  nil ]
   ["1s"  "1 second"    1      :darkgreen]
   ["5s"  "5 seconds"   5      :darkgreen]
   ["15s" "15 seconds"  15     :darkgreen]
   ["1m"  "1 minute"    60     :darkkhaki]
   ["5m"  "5 minutes"   300    :darkkhaki]
   ["15m" "15 minutes"  900    :darkkhaki]
   ["1h"  "1 hour"      3600   :mediumred]
   ["4h"  "4 hours"     14400  :mediumred]
   ["1d"  "1 day"       86400  :mediumred]])


(def DURATION-FILTER-ITEMS
  (for [[k l t c] DURATIONS]
    {:key k, :text l, :icon [:awe (if t :clock :cancel) (if t c :red)],
     :on-click [::filter-list [:view :trace :list :filter :min-duration :selected] t]}))

(def TIME-F (ctf/formatter "yyyy-MM-dd"))

; TODO this is too complicated; filters need to have its own unified data structure working on single loop
(defn clear-filter-items []
  (let [filter (zs/subscribe [:get [:view :trace :list :filter]])
        search (zs/subscribe [:get [:view :trace :list :search]])]
    (ra/reaction
      (let [filter @filter, search @search
            ff (for [[k v] filter :when (:selected v), :let [kk (str k)]
                     :let [txt (case k :time (ctf/unparse TIME-F (:selected v)),
                                       :errors-only "(errors only)"
                                       :min-duration (str "> " (:selected v) " sec"), kk)]
                     :let [glyph (case k :time :calendar, :min-duration :clock,
                                         :errors-only :attention-circled, :filter)]]
                 {:key      kk, :text txt, :icon [:awe glyph :red],
                  :on-click [:do [:set [:view :trace :list :filter k] {}] [::refresh-list true]]})
            fs (when-not (empty? (:text search))
                 [{:key      ":text", :text (str \" (zu/ellipsis (:text search) 32) \"), :icon [:awe :search :red],
                   :on-click [:do [:set [:view :trace :list :search] {}] [::refresh-list true]]}])]
        (doall
          (concat
            [{:key "all", :text "clear filters", :icon [:awe :cancel :red]
              :on-click [:do
                         [:set [:view :trace :list :filter] {}]
                         [:set [:view :trace :list :search] {}]
                         [::refresh-list true]]}]
            ff fs)))
      )))


(def CLEAR-FILTER-ITEMS (clear-filter-items))


(defn toolbar-right []
  (let [view-state (zs/subscribe [:get [:view :trace :list]])
        filter-defs (zs/subscribe [:get [:config :filters]])
        filter-vals (zs/subscribe [:get [:data :filters]])]
    (fn []
      (let [view-state @view-state]
        [:div.flexible.flex
         (zw/svg-button :awe :attention-circled :red "Errors only"
           [:do [:toggle [:view :trace :list :filter :errors-only :selected]] [::refresh-list]]
           :opaque (get-in view-state [:filter :errors-only :selected]))
         (zw/svg-button :awe :calendar :light "Select timespan"
           [:popup/open :calendar, :position :top-right, :on-click-kw ::filter-list,
            :path [:view :trace :list :filter :time]]
           :opaque (get-in view-state [:filter :time :selected]))
         (zw/svg-button :awe :clock :light "Minimum duration"
           [:popup/open :menu, :position :top-right, :items DURATION-FILTER-ITEMS
            :path [:view :trace :list :filter :min-duration :selected]]
           :opaque (get-in view-state [:filter :min-duration :selected]))
         (doall
           (for [{:keys [attr description icon]} @filter-defs
                 :let [[f g c] (zu/glyph-parse icon "awe/filter#light")]
                 :let [path [:view :trace :list :filter attr :selected]]]
             ^{:key attr}
             [:div
              (zw/svg-button
                f g c description
                [:popup/open :menu :position :top-right,
                 :items (doall
                          (cons
                            {:key "nil", :text "clear filter", :icon [:awe :cancel :red],
                             :on-click [:do [:set path nil] [::refresh-list true]]}
                            (for [name (get @filter-vals attr)]
                              {:key      name, :text name, :icon [f g c]
                               :on-click [:do [:set path name] [::refresh-list true]]})))]
                :opaque (some? (get-in view-state [:filter attr :selected])))]))
         (zw/svg-button :awe :cancel :red "Clear filters."
           [:popup/open :menu :position :top-right :items CLEAR-FILTER-ITEMS]
           :opaque (> (count @CLEAR-FILTER-ITEMS) 1))
         ]))))


(defn toolbar-left []
  (let [view-state (zs/subscribe [:get [:view :trace :list]])]
    (fn []
      (let [{:keys [sort show-stats deep-search show-hosts]} @view-state]
        [:div.flexible.flex
         (zw/svg-button
           :awe :sort-alt-down :light "Sort by duration"
           [::filter-list [:view :trace :list :sort :dur] (not (:dur sort))]
           :opaque (:dur sort))
         (zw/svg-button
           :awe :chart-bar :light "Show stats"
           [:toggle [:view :trace :list :show-stats]]
           :opaque show-stats)
         (zw/svg-button
           :awe :cubes :light "Show origin hosts"
           [:toggle [:view :trace :list :show-hosts]]
           :opaque show-hosts)
         (zw/svg-button
           :awe :object-group :light "Group Spans"
           [:do [:toggle [:view :trace :list :deep-search]] [::refresh-list true]]
           :opaque deep-search)
         ])
      )))


(defn trace-list [params]
  "Trace seach/listing panel: [:view :trace :list]"
  (zs/dispatch [::parse-filter-query (:q (:params params))])
  (zs/dispatch [::refresh-list])
  (zws/render-screen
    :toolbar [zws/list-screen-toolbar
              :vpath [:view :trace :list]
              :title "Traces",
              :add-left [toolbar-left],
              :add-right [toolbar-right]
              :sort-ctls {},
              :search-box true,
              :on-refresh [::refresh-list true]]
    :central [zws/list-interior
              :vpath [:view :trace :list]
              :data [:data/trace-list-list]
              :render-item (zvmt/render-trace-list-item-fn :dtrace-links true, :attr-links false)
              :render-details (zvmt/render-trace-list-detail-fn true true)
              :id "zorka-traces",
              :id-attr :tid,
              :class "trace-list-list",
              :on-scroll [::scroll-list],
              :on-click (zvmt/trace-list-click-handler-fn :trace :list)]))


(zws/defscreen "mon/trace/list" trace-list)


(defn dtrace-toolbar-left []
  [:div.flexible.flex
   (zw/svg-button
     :awe :chart-bar :light "Show stats"
     [:toggle [:view :trace :list :show-stats]]
     :opaque zvmt/SHOW-STATS)
   (zw/svg-button
     :awe :cubes :light "Show origin hosts"
     [:toggle [:view :trace :list :show-hosts]]
     :opaque zvmt/SHOW-HOSTS)])


(defn chunks-to-list [{:keys [children] :as t} depth]
  (let [t (assoc t :tid (zu/to-tid t) :depth depth)]
    (apply concat [t] (for [c children] (chunks-to-list c (inc depth))))))


(zs/reg-event-db
  ::handle-dtrace-result
  (fn [db [_ trace]]
    (assoc-in db [:data :dtrace :tree] (chunks-to-list trace 0))))


(defn dtrace-tree [{{:keys [tid]} :params}]
  "Displays distributed trace panel [:view :trace :dtrace]"
  (zs/dispatch-sync
    [:set [:data :dtrace :tree] []])
  (zs/dispatch
    [:xhr/get (io/api "/trace/" (:traceid (zu/parse-tid tid))) nil nil
     :on-success [::handle-dtrace-result],
     :on-error zv/DEFAULT-SERVER-ERROR])
  (zws/render-screen
    :hide-menu-btn true
    :toolbar [zws/list-screen-toolbar
              :vpath [:view :dtrace :tree]
              :title     "Distributed tracing",
              :sort-ctls {}
              :add-left  [dtrace-toolbar-left]]
    :central [zws/list-interior
              :vpath [:view :dtrace :tree]
              :data [:get [:data :dtrace :tree]]
              :render-item (zvmt/render-trace-list-item-fn :dtrace-links false)
              :render-details (zvmt/render-trace-list-detail-fn false false)
              :id-attr :tid,
              :id "zorka-dist",
              :class "trace-list-list",
              :on-click (zvmt/trace-list-click-handler-fn :dtrace :tree)]
    ))


(zws/defscreen "mon/trace/dtree" dtrace-tree)

