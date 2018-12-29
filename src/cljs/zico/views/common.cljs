(ns zico.views.common
  (:require
    [zico.util :as zu]
    [zico.state :as zs]
    [zico.popups :as zp]
    [zico.widgets :as zw]))


(def MENU-INITIAL-STATE
  {:open? true})


(zs/dispatch [:once :set [:view :menu] MENU-INITIAL-STATE])


(defn menu-item [family glyph label path {:keys [changed?] :as params}]
  [:div.itm {:on-click (zs/to-handler [:to-screen path]), :title label}
   [(if changed? :div.ico.icm :div.ico) (zw/svg-icon family glyph "text")]])


(defn main-menu []
  (let [menu-state (zs/subscribe [:get [:view :menu]])]
    (fn []
      (let [{:keys [open?]} @menu-state]
        [(if open? :div.main-menu.display-inline :div.main-menu.display-none)
         [:div.pnl
          [:div.itm (zw/svg-button :awe :menu :white "Close menu" [:toggle [:view :menu :open?]])]
          [:div.itm ""]
          [:div.itm (zw/svg-button :awe :search :white "Search" [:to-screen "mon/trace/list"])]
          [:div.itm (zw/svg-button :awe :cube :white "Applications" [:to-screen "cfg/app/list"])]
          [:div.itm (zw/svg-button :awe :sitemap :white "Environments" [:to-screen "cfg/env/list"])]
          [:div.itm (zw/svg-button :awe :home :white "Hosts" [:to-screen "cfg/host/list"])]
          [:div.itm (zw/svg-button :awe :tags :white "Trace types" [:to-screen "cfg/ttype/list"])]
          [:div.itm (zw/svg-button :awe :lightbulb :white "Registrations" [:to-screen "cfg/hostreg/list"])]
          [:div.itm (zw/svg-button :awe :user :white "Users" [:to-screen "cfg/user/list"])]
          [:div.itm (zw/svg-button :awe :hdd :white "Backup" [:to-screen "adm/backup"])]
          ]]))))


(def USER-MENU-ITEMS
  [{:key :about, :text "About ...",
    :icon [:awe :info-circled  :blue],
    :on-click [:to-screen "user/about" {}]}
   {:key :prefs, :text "My profile",
    :icon [:awe :user], :on-click [:to-screen "user/prefs" {}]}
   {:key :logout, :text "Logout",
    :icon [:awe :logout], :on-click [:set-location "/logout"]}])


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


(defn render-status-bar [{:keys [level message]}]
  (when level
    [:div.status-bar
     [(case level :error :div.c-red :div.c-text) message]
     (zw/svg-button
       :awe :cancel :red "Close"
       [:set [:view :status-bar] nil])])
  )


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

(def DEFAULT-SERVER-ERROR [:status/message :error 5000 "Server error: "])
(def DEFAULT-STATUS-ERROR [:status/message :error 5000 "Error: "])
(def DEFAULT-STATUS-INFO [:status/message :info 5000 ""])

(defn render-screen [& {:keys [toolbar caption central hide-menu-btn]}]
  "Renders whole screen that consists of main panel, toolbar and menu bar."
  (let [menu-state (zs/subscribe [:get [:view :menu]])
        status-bar (zs/subscribe [:get [:view :status-bar]])]
    (fn []
      (let [{:keys [open?]} @menu-state]
        [:div.top-container
         [main-menu]
         [:div.main
          [:div.toolbar
           [(if (or open? hide-menu-btn) :div.itm.display-none :div.itm)
            (zw/svg-button :awe :menu :light "Open menu" [:toggle [:view :menu :open?]])]
           (or toolbar [:div.flexible.flex [:div.cpt caption]])
           (zw/svg-button
             :awe :logout :light "User settings & logout"
             [:popup/open :menu, :caption "User Menu",
              :position :top-right, :items USER-MENU-ITEMS])]
          [:div.central-panel central]
          (render-status-bar @status-bar)]
         [zp/render-popup-stack]
         ]))))

