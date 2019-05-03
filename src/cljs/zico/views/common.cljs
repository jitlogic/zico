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
          [:div.itm (zw/svg-button :awe :search :white "Search" [:to-screen "mon/trace/list"])]]]))))


(def USER-MENU
  (zw/svg-button
    :awe :logout :light "User settings & logout"
    [:popup/open :menu, :caption "User Menu",
     :position :top-right,
     :items
     [{:key :logout, :text "Logout",
       :icon [:awe :logout], :on-click [:set-location "/logout"]}]]))


(def DEFAULT-SERVER-ERROR [:status/message :error 5000 "Server error: "])
(def DEFAULT-STATUS-ERROR [:status/message :error 5000 "Error: "])
(def DEFAULT-STATUS-INFO [:status/message :info 5000 ""])



