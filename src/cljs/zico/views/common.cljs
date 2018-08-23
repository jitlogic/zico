(ns zico.views.common
  (:require
    [zico.util :as zu]
    [zico.state :as zs]
    [zico.popups :as zp]
    [zico.widgets :as zw]))


(def MENU-INITIAL-STATE
  {:monitor true,
   :configure true,
   :recent true,
   :admin true,
   :user true,
   :bookmarks true})


(zs/dispatch [:once :set [:view :menu] MENU-INITIAL-STATE])


(defn menu-section [open? kw label]
  [:div.scn {:on-click (zs/to-handler [:toggle [:view :menu kw]])}
   [:div.ic (zw/svg-icon :awe (if open? :down-dir :right-dir) :gray)]
   [:div label]])


(defn menu-item [family glyph label path {:keys [changed?] :as params}]
  [:div.itm {:on-click (zs/to-handler [:to-screen path])}
   [(if changed? :div.ico.icm :div.ico)
    (zw/svg-icon family glyph "text")] [:div.lead label]])


(defn main-menu []
  (let [menu-state (zs/subscribe [:get [:view :menu]])]
    (fn []
      (let [{:keys [open? monitor configure admin]} @menu-state]
        [(if open? :div.main-menu.display-inline :div.main-menu.display-none)
         [:div.toolbar
          (zw/svg-button :awe :menu :white "Close menu" [:toggle [:view :menu :open?]])
          [:div.cpt.small-or-less "zorka"]
          [:div.icon-placeholder]]
         [:div.pnl
          (menu-section monitor :monitor "TRACES")
          (when monitor
            [:div
             (menu-item "awe" "search" "Search" "mon/trace/list" {})
             ;(menu-item "ent" "flow-cascade" "Distributed" "mon/dtrace/tree" {})
             ])
          (menu-section configure :configure "CONFIGURE")
          (when configure
            [:div
             (menu-item "awe" "cube" "Applications" "cfg/app/list" {})
             (menu-item "awe" "sitemap" "Environments" "cfg/env/list" {})
             (menu-item "awe" "home" "Hosts" "cfg/host/list" {})
             (menu-item "awe" "tags" "Trace types" "cfg/ttype/list" {})
             (menu-item "awe" "lightbulb" "Registrations" "cfg/hostreg/list" {})])

          (menu-section admin :admin "ADMIN")
          (when admin
            [:div
             (menu-item "awe" "user" "Users" "cfg/user/list" {})
             ;(menu-item "awe" "users" "Groups" "cfg/group/list" {})
             (menu-item "awe" "hdd" "Backup" "adm/backup" {})])
          ;(menu-section bookmarks :bookmarks "BOOKMARKS")
          ;(when bookmarks
          ;  [:div nil])                                     ; TODO render bookmarks here
          ;(menu-section recent :recent "RECENT")
          ;(when recent
          ;  [:div nil])                          ; TODO render recent items here
          ]]))))


(def USER-MENU-ITEMS
  [{:key :about, :text "About ...",
    :icon [:awe :info-circled  :blue],
    :on-click [:to-screen "user/about" {}]}
   {:key :prefs, :text "My profile",
    :icon [:awe :user], :on-click [:to-screen "user/prefs" {}]}
   ;{:key :sep1, :separator? true}
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


(defn list-interior [[sectn view] render-item render-selected & {:keys [id id-attr on-scroll on-click class] :or {id-attr :uuid}}]
  "Rennders generic list interior. To be used as :central part of screens."
  (let [subscr (keyword "data" (str (name sectn) "-" (name view) "-list")), data (zs/subscribe [subscr])
        selected (zs/subscribe [:get [:view sectn view :selected]])
        root-tag (keyword (str "div.list." (or class (name subscr)) ".full-h"))
        attrs (zu/no-nulls {:id id, :on-scroll (on-scroll-fn on-scroll), :on-click on-click})]
    (fn []
      (let [data @data, selected @selected]
        [root-tag attrs
         (doall
           (for [obj data :let [id (id-attr obj)]]
             ^{:key id}
             (if (= selected id)
               (render-selected obj)
               (render-item obj))))]))))


(def DEFAULT-SORT-CTLS {:alt "Sort order"})


; TODO dopracować logikę sort - zrobić osobny handler do tej logiki
(defn sort-event [sectn view order]
  [:do
   [:toggle [:view sectn view :sort :rev]]
   [:set [:view sectn view :sort :order] order]])


; TODO sort-ctrs - powinno być puste by default
; TODO wyłączyć search by default
(defn list-screen-toolbar [sectn view {:keys [title add-left add-right sort-ctls flags on-refresh]}]
  "Renders toolbar interior for list screen (with search box, filters etc.)"
  (let [view-state (zs/subscribe [:get [:view sectn view]])
        sort-ctls (or sort-ctls DEFAULT-SORT-CTLS)]
    (fn []
      (let [view-state @view-state, search-state (:search view-state), sort-state (:sort view-state)]
        [:div.flexible.flex
         [:div.flexible.flex.itm
          (when-not (:no-refresh flags)
            (zw/svg-button :awe :arrows-cw :blue "Refresh" (or on-refresh [:data/refresh sectn view])))
          ; TODO sort-ctrls is bad abstraction in current form; refactor or remove;
          (when (:alt sort-ctls)
            (zw/svg-button
              :awe (if (and (= :alt (:order sort-state)) (-> view-state :sort :rev)) :sort-alt-down :sort-alt-up)
              :light (:alt sort-ctls "Sort order"), (sort-event sectn view :alt), :opaque (= :alt (:order sort-state))))
          (when (:name sort-ctls)
            (zw/svg-button
              :awe (if (and (= :name (:order sort-state)) (-> view-state :sort :rev)) :sort-name-down :sort-name-up)
              :light (:name sort-ctls "Sort order"), (sort-event sectn view :number), :opaque (= :name (:order sort-state))))
          (when (:number sort-ctls)
            (zw/svg-button
              :awe (if (and (= :number (:order sort-state)) (-> view-state :sort :rev)) :sort-number-down :sort-number-up)
              :light (:number sort-ctls "Sort order") (sort-event sectn view :number) :opaque (= :number (:order sort-state))))
          add-left]
         [:div.flexible.flex
          (if (:open? search-state)
            (let [path [:view sectn view :search]]
              [zw/input
               :path path, :tag-ok :input.search, :autofocus true,
               :getter (zs/subscribe [:get path]),
               :setter [:form/set-text path :nil]
               :on-update [:timer/update path 1000 nil on-refresh]
               :on-key-esc [:do [:timer/cancel path] [:set path {}] on-refresh]
               :on-key-enter [:do [:timer/flush path] [:set (conj path :open?) false]]])
            [:div.cpt                                       ; TODO przenieść tę część do search boxa
             {:on-click (zs/to-handler [:toggle [:view sectn view :search :open?]])}
             (str title (if (:text search-state) (str " (" (:text search-state) ")") ""))])]
         [:div.flexible.flex.itm
          (zw/svg-button :awe :search :light "Search hosts." [:toggle [:view sectn view :search :open?]]
                         :opaque (:open? search-state))
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

