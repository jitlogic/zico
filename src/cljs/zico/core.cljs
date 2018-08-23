(ns zico.core
  (:require
    [reagent.core :as rc]
    [reagent.session :as rs]
    [secretary.core :as sc :include-macros true]
    [accountant.core :as ac]
    [zico.state :as zs]
    [zico.io :as io]
    [zico.forms :as zf]
    [zico.views.cfg :as zvc]
    [zico.views.mon-trace-list]
    [zico.views.mon-trace-dist]
    [zico.views.mon-trace-tree]
    [zico.views.mon-trace-stats]
    [zico.views.adm-backup :as zvbkp]
    [zico.views.user-about :as zvabt]
    [zico.views.user-prefs :as zvupr]))



;; Load basic dictionary data and initialize UI state

(zs/dispatch [:once :set [:view :menu :open?] false])



(defn current-page []
  (if-let [[view {params :query-params}] (rs/get :current-page)]
    [:div#top [view params]]
    [:div "No such view."]))


(defn main-routes [& {:as routes}]
  (doseq [[path page] routes]
    (sc/defroute
      (str "/view/" path) {:as p}
      (rs/put! :current-page [page p path]))))


(main-routes
  ; Monitoring screens
  "mon/trace/list"    #'zico.views.mon-trace-list/trace-list
  "mon/trace/tree"    #'zico.views.mon-trace-tree/trace-tree
  "mon/dtrace/tree"   #'zico.views.mon-trace-dist/dtrace-tree
  "mon/trace/stats"   #'zico.views.mon-trace-stats/trace-stats

  ; Config items - lists
  "cfg/app/list"     #'zvc/app-list
  "cfg/env/list"     #'zvc/env-list
  "cfg/host/list"    #'zvc/host-list
  "cfg/user/list"    #'zvc/user-list
  "cfg/group/list"   #'zvc/group-list
  "cfg/ttype/list"   #'zvc/ttype-list
  "cfg/hostreg/list" #'zvc/hostreg-list

  ; Config items - editors
  "cfg/app/edit"     #'zvc/app-edit
  "cfg/env/edit"     #'zvc/env-edit
  "cfg/host/edit"    #'zvc/host-edit
  "cfg/user/edit"    #'zvc/user-edit
  "cfg/group/edit"   #'zvc/group-edit
  "cfg/ttype/edit"   #'zvc/ttype-edit
  "cfg/hostreg/edit" #'zvc/hostreg-edit

  ; Various other things
  "adm/backup"   #'zvbkp/adm-backup-screen
  "user/about"   #'zvabt/render-about-screen
  "user/prefs"   #'zvupr/render-user-prefs
  )



;; -------------------------

(defn mount-root []
  (rc/render [current-page] (.getElementById js/document "app")))


(defn init! []
  (ac/configure-navigation!
    {:nav-handler (fn [path] (sc/dispatch! path))
     :path-exists? (fn [path] (sc/locate-route path))})
  (ac/dispatch-current!)
  (mount-root))

