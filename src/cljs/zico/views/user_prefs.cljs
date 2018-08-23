(ns zico.views.user-prefs
  (:require
    [reagent.ratom :as ra]
    [zico.state :as zs]
    [zico.views.common :as zv]
    [zico.widgets :as zw]))

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
                 (let [{:keys [status], {old :value} :old, {new :value} :new, {rep :value} :rep} @state]
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
           [zw/input
            :path [:view :user :prefs :old],
            :getter (zs/subscribe [:get [:view :user :prefs :old]]),
            :setter [:form/set-text [:view :user :prefs :old] :nil],
            :attrs {:type :password}]]]
         [:div.form-row
          [:div.col1.label "New password:"]
          [:div.col2
           [zw/input
            :path [:view :user :prefs :new],
            :getter (zs/subscribe [:get [:view :user :prefs :new]]),
            :setter [:form/set-text [:view :user :prefs :new] :nil],
            :attrs {:type :password}, :valid? match?]]
          [:div.aux (passwd-strength (:value new))]]
         [:div.form-row
          [:div.col1.label "Repeat password:"]
          [:div.col2
           [zw/input
            :path [:view :user :prefs :rep],
            :getter (zs/subscribe [:get [:view :user :prefs :rep]]),
            :setter [:form/set-text [:view :user :prefs :new] :nil],
            :attrs {:type :password}, :valid? match?]]
          (when-not @match?
            [:div.aux [:div.i.c-red "Passwords don't match"]])]
         [:div.button-row
          [zw/button
           {:text     "Change", :icon [:ent :key :yellow], :enabled? ready?,
            :on-click [:xhr/post "/user/password" nil
                       {:oldPassword (:value old), :newPassword (:value new), :repeatPassword (:value rep)}
                       :on-success [:set [:view :user :prefs]
                                    {:status :ok :msg "Password changed."}]
                       :on-error [:do
                                  [:set [:view :user :prefs :status] :error]
                                  [:set [:view :user :prefs :msg] "Password change failed."]]]}]]]
        ))))

(defn render-user-prefs []
  (zv/render-screen
    :caption "User preferences"
    :central [user-prefs-panel]))

