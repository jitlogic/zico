(ns zico.widgets.screen
  (:require
    [reagent.session :as rs]
    [zico.widgets.popups :as zp]
    [zico.widgets.widgets :as zw]
    [zico.widgets.state :as zs]
    [zico.widgets.util :as zu]
    [zico.widgets.io :as io]))


(def MENU-INITIAL-STATE {:open? false})

(zs/dispatch [:once :set [:view :menu] MENU-INITIAL-STATE])

(defn render-status-bar [{:keys [level message]}]
  (when level
    [:div.status-bar
     [(case level :error :div.c-red :div.c-text) message]
     (zw/svg-button
       :awe :cancel :red "Close"
       [:set [:view :status-bar] nil])]))


(defn render-screen [& {:keys [toolbar caption central user-menu]}]
  "Renders whole screen that consists of main panel, toolbar and menu bar."
  (let [status-bar (zs/subscribe [:get [:view :status-bar]])]
    (fn []
      [:div.top-container
       [:div.main
        [:div.toolbar
         (or toolbar [:div.flexible.flex [:div.cpt caption]])
         (when user-menu) user-menu]
        [:div.central-panel central]
        (render-status-bar @status-bar)]
       [zp/render-popup-stack]
       ])))


(defn on-scroll-fn [on-scroll]
  (when on-scroll
    (fn [e]
      (let [c (.-target e)]
        (zs/dispatch
          (conj on-scroll
                {:scroll-top    (.-scrollTop c)
                 :scroll-height (.-scrollHeight c),
                 :offset-height (.-offsetHeight c)})
          )))))


(defn list-interior [&{:keys [vpath data id id-attr on-scroll on-click class render-item render-details] :or {id-attr :id}}]
  "Rennders generic list interior. To be used as :central part of screens."
  (let [data (if (vector? data) (zs/subscribe data) data)
        selected (zs/subscribe [:get (vec (concat vpath [:selected]))])
        root-tag (keyword (str "div.list." class ".full-h"))
        attrs (zu/no-nulls {:id id, :on-scroll (on-scroll-fn on-scroll), :on-click on-click})]
    (fn []
      (let [data @data, selected @selected]
        [root-tag attrs
         (doall
           (for [obj data :let [id (id-attr obj)]]
             ^{:key id}
             (if (= selected id)
               (render-details obj)
               (render-item obj))))]))))


(defn status-message-handler [{:keys [db]} [_ level timeout prefix message ctime]]
  (let [tstamp (or ctime (.getTime (js/Date.)))]
    {:db       (assoc-in db [:view :status-bar] {:level level, :message (str prefix message), :tstamp tstamp})
     :dispatch [:set-timeout timeout [:status/clean tstamp]]
     }))

(defn status-clean-handler [db [_ tstamp]]
  (if (= tstamp (-> db :view :status-bar :tstamp))
    (assoc-in db [:view :status-bar] nil)
    db))

(zs/reg-event-fx :status/message status-message-handler)
(zs/reg-event-db :status/clean status-clean-handler)


(defn sort-event [vpath order]
  [:do
   [:toggle (concat vpath [:sort :rev])]
   [:set (concat vpath [:sort :order]) order]])


(defn list-screen-toolbar [&{:keys [vpath title add-left add-right sort-ctls on-refresh search-box]}]
  "Renders toolbar interior for list screen (with search box, filters etc.)"
  (let [view-state (zs/subscribe [:get vpath])]
    (fn []
      (let [view-state @view-state, search-state (:search view-state), sort-state (:sort view-state)]
        [:div.flexible.flex
         [:div.flexible.flex.itm
          (when on-refresh
            (zw/svg-button :awe :arrows-cw :blue "Refresh" on-refresh))
          (when (:alt sort-ctls)
            (zw/svg-button
              :awe (if (and (= :alt (:order sort-state)) (-> view-state :sort :rev)) :sort-alt-down :sort-alt-up)
              :light (:alt sort-ctls "Sort order"), (sort-event vpath :alt), :opaque (= :alt (:order sort-state))))
          (when (:name sort-ctls)
            (zw/svg-button
              :awe (if (and (= :name (:order sort-state)) (-> view-state :sort :rev)) :sort-name-down :sort-name-up)
              :light (:name sort-ctls "Sort order"), (sort-event vpath :number), :opaque (= :name (:order sort-state))))
          (when (:number sort-ctls)
            (zw/svg-button
              :awe (if (and (= :number (:order sort-state)) (-> view-state :sort :rev)) :sort-number-down :sort-number-up)
              :light (:number sort-ctls "Sort order") (sort-event vpath :number) :opaque (= :number (:order sort-state))))
          add-left]
         [:div.flexible.flex
          (if (:open? search-state)
            (let [path (concat vpath [:search]), tpath (concat path [:text])]
              [zw/autofocus
               [zw/input
                :path path, :tag-ok :input.search,
                :getter (zs/subscribe [:get tpath]),
                :setter [:set tpath]
                :on-update [:timer/update path 1000 nil on-refresh]
                :on-key-esc [:do [:timer/cancel path] [:set path {}] on-refresh]
                :on-key-enter [:do [:timer/flush path] [:set (concat path [:open?]) false]]]])
            [:div.cpt
             (if search-box
               {:on-click (zs/to-handler [:toggle (concat vpath [:search :open?])])})
             (str title (if (:text search-state) (str " (" (:text search-state) ")") ""))])]
         [:div.flexible.flex.itm
          (if search-box
            (zw/svg-button :awe :search :light "Search hosts." [:toggle (concat vpath [:search :open?])]
                           :opaque (:open? search-state)))
          add-right]
         ]))))


(defonce SCREENS
  (atom {:default [:div.splash-centered [:div.splash-frame "No such view."]]}))


(defn current-screen [user-info on-error]
  (fn []
    (let [{:keys [query anchor]} @io/CURRENT-PAGE,
          view (get @SCREENS anchor (get @SCREENS :default))]
      (cond
        (nil? anchor) [:div "NULL VIEW !!!"]
        (nil? view) [:div "No such view" anchor]
        :else [:div#top [view {:params query}]
               ]))))


(defn defscreen [path view]
  (swap! SCREENS assoc path view))

