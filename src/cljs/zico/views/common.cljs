(ns zico.views.common
  (:require
    [zico.widgets.state :as zs]
    [zico.widgets.widgets :as zw]
    [zico.widgets.screen :as zws]))

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
          (if (zws/has-role :admin)
            [:div.itm (zw/svg-button :awe :user :white "Users" [:to-screen "cfg/user/list"])])
          (if (zws/has-role :admin)
            [:div.itm (zw/svg-button :awe :hdd :white "Backup" [:to-screen "adm/backup"])])
          ]]))))


(def USER-MENU
  (zw/svg-button
    :awe :logout :light "User settings & logout"
    [:popup/open :menu, :caption "User Menu",
     :position :top-right,
     :items
     [{:key :about, :text "About ...",
       :icon [:awe :info-circled  :blue],
       :on-click [:to-screen "user/about" {}]}
      {:key :prefs, :text "My profile",
       :icon [:awe :user], :on-click [:to-screen "user/prefs" {}]}
      {:key :logout, :text "Logout",
       :icon [:awe :logout], :on-click [:set-location "/logout"]}]]))


(def DEFAULT-SERVER-ERROR [:status/message :error 5000 "Server error: "])
(def DEFAULT-STATUS-ERROR [:status/message :error 5000 "Error: "])
(def DEFAULT-STATUS-INFO [:status/message :info 5000 ""])



