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
  (let [vroot (-> db :view :trace :list)
        text (-> vroot :search :text)
        qmi (into
              {:type :qmi}
              (for [k [:ttype :app :env :min-duration :host]
                    :let [v (-> vroot :filter k :selected)]
                    :when v]
                {k v}))
        qmi (merge
              qmi
              (when-let [tstamp (-> vroot :filter :time :selected)]
                {:tstart (ctf/unparse PARAM-FORMATTER tstamp)
                 :tstop (ctf/unparse PARAM-FORMATTER (zw/day-next tstamp))}))
        tft (if-not (empty? text) {:type :text, :text text})
        all (for [[k v] (:filter-attrs vroot) :when (string? k)] {:type :kv, :key k, :val v})
        all (for [f (into [tft] all) :when (some? f)] f)
        q (cond
            (> (count all) 1) {:type :and, :args (vec all)}
            (= (count all) 1) (first all)
            :else nil)
        deep-search (= true (-> db :view :trace :list :deep-search))]
    (merge {:limit 50, :offset offset, :deep-search deep-search, :node q, :qmi qmi})))


(defn parse-filter-query [db [_ q]]
  (if q
    (let [f (read-string (js/LZString.decompressFromBase64 q)),
          {{:keys [env app ttype min-duration tstart host]} :qmi :keys [deep-search node]} f,
          fltr {:env   {:selected env}, :app {:selected app}, :host {:selected host},
                :ttype {:selected ttype}, :min-duration {:selected min-duration}
                :time  {:selected (if tstart (ctf/parse PARAM-FORMATTER tstart))}}
          nodes (if (= :and (:type node)) (:args node) [node])
          text (first (for [n nodes :when (= :text (:type n))] (:text n)))
          attrs (into {} (for [n nodes :when (= :kv (:type n))] {(:key n), (:val n)}))]
      (assoc-in
        db [:view :trace :list]
        (->
          (get-in db [:view :trace :list])
          (assoc-in [:filter] fltr)
          (assoc-in [:deep-search] deep-search)
          (assoc-in [:search :text] text)
          (assoc-in [:filter-attrs] attrs)
          )))
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
    (let [d0 (if clean {} (get-in db [:data :trace :list] {}))
          sel (get-in db [:view :trace :list :selected])
          uuids (into #{} (map :uuid data))
          evt (if (and sel (contains? uuids sel))
                [:xhr/get (io/api "/trace/" sel "?depth=1") [:data :trace :list sel :detail] nil
                 :on-error zv/DEFAULT-SERVER-ERROR]
                [:nop])]
      {:db       (assoc-in db [:data :trace :list] (into d0 (for [d data] {(:uuid d) d})))
       :dispatch [:do evt [::extend-list-notification data]]})))


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


(defn filtered-items [path data icon & {:keys [filter-fn]}]
  (let [icon-fn (cond (vector? icon) (constantly icon), (fn? icon) icon, :else (constantly [:awe :paw :text]))
        selected (zs/subscribe [:get path])]
    (ra/reaction
      (doall
        (cons
          {:key "nil", :text "clear filter", :icon [:awe :cancel :red],
           :on-click [:do [:set path nil] [::refresh-list true]]}
          (for [{:keys [id name] :as r} (sort-by :name (vals (zu/deref? data)))
                :when ((or filter-fn identity) r)]
            {:key      id, :text name, :icon (icon-fn r),
             :on-click [:do [:set path id] [::refresh-list true]]
             :state    (if (= id @selected) :selected :normal)
             }))))))


(def TRACE-TYPE-ITEMS
  (filtered-items
    [:view :trace :list :filter :ttype :selected]
    (zs/subscribe [:get [:data :cfg :ttype]])
    #(zu/glyph-parse (:glyph %) "awe/list-alt#text")
    :filter-fn #(= 0x08 (bit-and (:flags %) 0x08))))


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
            f1 (for [[k lbl ic] [[:app "App: " :cubes] [:env "Env: " :sitemap] [:ttype "Type: " :list-alt]
                                 [:host "Host: " :sitemap] [:time "Time: " :calendar]]
                     :let [id (get-in filter [k :selected])] :when id]
                 {:key      (str k), :text (str lbl (or (get-in @cfg [k id :name]) id)), :icon [:awe ic :red],
                  :on-click [:do [:set [:view :trace :list :filter k] {}] [::refresh-list true]]})
            f2 (when-not (empty? (:text search))
                 [{:key      ":text", :text (str "Search: " (zu/ellipsis (:text search) 32)), :icon [:awe :search :red],
                   :on-click [:do [:set [:view :trace :list :search] {}] [::refresh-list true]]}])
            f3 (for [[k _] fattrs :when (string? k)]
                 {:key      (str "ATTR-" k), :text (zu/ellipsis (str "Attr: " k) 32), :icon [:awe :filter :red],
                  :on-click [:do [:dissoc [:view :trace :list :filter-attrs] k] [::refresh-list true]]})]
        (doall
          (concat
            [{:key "all", :text "clear filters", :icon [:awe :cancel :red]
              :on-click [:do
                         [:set [:view :trace :list :filter] {}]
                         [:set [:view :trace :list :filter-attrs] {}]
                         [:set [:view :trace :list :search] {}]
                         [::refresh-list true]]}]
            f1 f2 f3)))
      )))


(def CLEAR-FILTER-ITEMS (clear-filter-items))


(defn toolbar-right []
  (let [view-state (zs/subscribe [:get [:view :trace :list]])]
    (fn []
      (let [view-state @view-state]
        [:div.flexible.flex
         (zw/svg-button :awe :calendar :light "Select timespan"
                        [:popup/open :calendar, :position :top-right, :on-click-kw ::filter-list,
                         :path [:view :trace :list :filter :time]]
                        :opaque (get-in view-state [:filter :time :selected]))
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
           [:do [:toggle [:view :trace :list :deep-search]] [::refresh-list true]]
           :opaque deep-search)
         (zw/svg-button
           :awe :eye-off :light "Suppress details"
           [:toggle [:view :trace :list :suppress]]
           :opaque suppress)]))))


(defn trace-list [params]
  "Trace seach/listing panel: [:view :trace :list]"
  (zs/dispatch [::parse-filter-query (:q params)])
  (zs/dispatch [::refresh-list])
  (zs/dispatch [:do
                [:once [:data/refresh :cfg :ttype]]
                [:once [:data/refresh :cfg :app]]
                [:once [:data/refresh :cfg :env]]
                [:once [:data/refresh :cfg :host]]])
  (zws/render-screen
    :main-menu zv/main-menu
    :user-menu zv/USER-MENU
    :user-menu zv/USER-MENU
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
              :id-attr :uuid,
              :class "trace-list-list",
              :on-scroll [::scroll-list],
              :on-click (zvmt/trace-list-click-handler-fn :trace :list)]))

