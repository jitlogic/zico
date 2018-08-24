(ns zico.views.adm-backup
  (:require-macros [reagent.ratom :as ra])
  (:require
    [zico.state :as zs]
    [zico.popups :as zp]
    [zico.widgets :as zw]
    [zico.views.common :as zv]))

(def REFRESH-EVENT [:xhr/get "/admin/backup" [:data :admin :backup] nil
                    :on-error zv/DEFAULT-SERVER-ERROR])

(defn btn-restore-action [id]
  [:popup/open :msgbox, :modal? true,
   :caption "Restoring database",
   :text ["Restore will override current configuration.", "Proceed ?"],
   :buttons
   [{:id :ok, :text "Restore", :icon [:awe :upload :yellow]
     :on-click [:xhr/post (str "/admin/backup/" id "/restore") nil {}
                :on-success [:alert "Database restored."]
                :on-error zv/DEFAULT-SERVER-ERROR]}
    {:id :cancel, :text "Cancel", :icon [:awe :cancel :red]}]])

; TODO popups z informacjami i handlery - to po wdrożeniu uniweralnego popupu starowanego eventami;
(defn backup-panel []
  (let [backup-state (zs/subscribe [:get [:view :admin :backup]])
        backup-data (zs/subscribe [:get [:data :admin :backup]])
        backup-sel #(let [text (.. % -target -value)] (zs/dispatch-sync [:set [:view :admin :backup :selected] text]))
        can-restore? (ra/reaction (let [sel (:selected @backup-state)] (and (some? sel) (not= sel "0"))))]
    (fn []
      (let [{:keys [selected]} @backup-state, {:keys [id tstamp]} (first @backup-data)]
        [:div.form-screen.backup-screen
         [:h1.section-title "Backup"]
         [:div.button-row
          [:div.col1
           [zw/button
            :icon [:awe :download :green], :text "Backup",
            :on-click [:xhr/post "/admin/backup" nil {}
                       :on-success REFRESH-EVENT
                       :on-error zv/DEFAULT-SERVER-ERROR]]]
          [:div.col2
           [:div.label.ellipsis
            (str "Last: " (if (some? id) tstamp "<none>") )]]]
         [:h1.section-title "Restore"]
         [:div.button-row
          [:div.col1
           [zw/button
            :icon [:awe :upload :yellow], :text "Restore", :enabled? can-restore?
            :on-click (btn-restore-action selected)]]
          [:div.col2
           [:select.select.label {:value (or selected ""), :on-change backup-sel}
            [:option {:value "0"} "-- select backup --"]
            (for [{:keys [id tstamp]} @backup-data]
              ^{:key id} [:option {:value (str id)} (str "#" id " (" tstamp ")")])]]]
         ]))))


(defn adm-backup-screen []
  (zs/dispatch [:once REFRESH-EVENT])
  (zv/render-screen
    :caption "Backup / Restore"
    :central [backup-panel]
    :toolbar [:div.flexible.flex
              [:div.flexible.flex.itm
               (zw/svg-button :awe :arrows-cw :blue "Refresh" REFRESH-EVENT)]
              [:div.cpt "Backup"]]))

