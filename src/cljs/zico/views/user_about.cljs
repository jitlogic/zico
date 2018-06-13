(ns zico.views.user-about
  (:require-macros
    [zico.macros :refer [zorka-build-info zorka-version-info]])
  (:require [zico.state :as zs]
            [zico.views.common :as zv]
            [zico.widgets :as zw]))


(def VERSION (str "zorka " (zorka-version-info)))
(def BUILD (str "Build: " (zorka-build-info)))


(defn about-refresh []
  (zs/dispatch [:rest/get "/system/info" [:data :user :about]]))


(defonce about-timer (js/setInterval about-refresh 30000))


(defn render-about-interior []
  (let [state (zs/subscribe [:get [:data :user :about]])]
    (fn []
      (let [{:keys [vm-name vm-version home-dir uptime mem-used mem-max]} @state]
        [:div.form-screen.about-screen
         [:h2.section-title VERSION]
         [:div.center.ellipsis BUILD]
         [:div.center.ellipsis (str "Home: " home-dir)]
         [:div.center.paragraph.ellipsis vm-name]
         [:div.center.ellipsis (str "Version: " vm-version)]
         [:div.center.paragraph.ellipsis (str "Uptime: " uptime)]
         [:div.center.ellipsis
          (str "Memory: " (int (/ mem-used 1048576)) "/" (int (/ mem-max 1048576))
               " MB (" (int (* 100.0 (/ mem-used mem-max))) "%)")]
         ]))))


(defn render-about-screen []
  (zv/render-screen
    :caption "About zorka"
    :central [render-about-interior]))
