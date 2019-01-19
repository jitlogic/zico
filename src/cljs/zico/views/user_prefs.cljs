(ns zico.views.user-prefs
  (:require
    [reagent.ratom :as ra]
    [zico.widgets.state :as zs]
    [zico.views.common :as zv]
    [zico.widgets.widgets :as zw]
    [zico.widgets.screen :as zws]
    [zico.widgets.io :as io]))

(def RE-PASS [#".*[a-z].*" #".*[A-Z].*" #".*[0-9].*" #".*[^a-zA-Z0-9].*"])

(defn passwd-strength [s]
  (when s
    (let [l (if (string? s) (count s) 0)
          n (apply + (for [r RE-PASS :when (re-matches r s)] 1))]
      (cond
        (= l 0) nil
        (or (< l 7) (< n 3)) [:div.i.c-red "Weak"]
        (or (< l 12) (< n 4)) [:div.i.c-yellow "Medium"]
        :else [:div.i.c-green "Strong"]))))

(defn user-prefs-panel []
  (let [state (zs/subscribe [:get [:view :user :prefs]])
        match? (ra/reaction (= (-> @state :new :value) (-> @state :rep :value)))
        ready? (ra/reaction
                 (let [{:keys [status old new rep]} @state]
                   (and (not= status :wait) (not (empty? old)) (not (empty? new)) (= new rep))))]
    (fn []
      (let [{:keys [status msg new old rep]} @state]
        [:div.form-screen.user-prefs-screen
         [:h1.section-title "Password"]
         (case status
           :error [:div.form-row.msg.c-red msg]
           :wait [:div.form-row.msg.c-gray msg]
           :ok [:div.form-row.msg.c-green msg]
           [:div.form-row.msg
            "Enter passwords and click Change button."])
         [:div.form-row
          [:div.col1.label "Old password:"]
          [:div.col2
           [zw/autofocus
            [zw/input
             :getter (zs/subscribe [:get [:view :user :prefs :old]]),
             :setter [:set [:view :user :prefs :old]],
             :attrs {:type :password}]]]]
         [:div.form-row
          [:div.col1.label "New password:"]
          [:div.col2
           [zw/input
            :getter (zs/subscribe [:get [:view :user :prefs :new]]),
            :setter [:set [:view :user :prefs :new]],
            :attrs {:type :password}, :valid? match?]]
          [:div.aux (passwd-strength (:value new))]]
         [:div.form-row
          [:div.col1.label "Repeat password:"]
          [:div.col2
           [zw/input
            :getter (zs/subscribe [:get [:view :user :prefs :rep]]),
            :setter [:set [:view :user :prefs :rep]],
            :attrs {:type :password}, :valid? match?]]
          (when-not @match?
            [:div.aux [:div.i.c-red "Passwords don't match"]])]
         [:div.button-row
          [zw/button
           :text     "Change", :icon [:ent :key :yellow], :enabled? ready?,
           :on-click [::change-password]]]]
        ))))

(zs/reg-event-fx
  ::change-password
  (fn [{:keys [db]}]
    (let [{:keys [old new rep]} (get-in db [:view :user :prefs])]
      {:db       (assoc-in db [:view :user :prefs] nil)
       :dispatch [:xhr/post (io/api "/user/password") nil
                  {:oldPassword    old, :newPassword    new, :repeatPassword rep}
                  :on-success
                  [:set [:view :user :prefs] {:status :ok :msg "Password changed."}]
                  :on-error
                  [:do
                   [:set [:view :user :prefs :status] :error]
                   [:set [:view :user :prefs :msg] "Password change failed."]]]
       })))

(defn render-user-prefs []
  (zws/render-screen
    :main-menu zv/main-menu
    :user-menu zv/USER-MENU
    :caption "User preferences"
    :central [user-prefs-panel]))

