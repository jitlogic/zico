(ns zico.core
  (:require
    [reagent.core :as rc]
    [accountant.core :as ac]
    [zico.widgets.state :as zs]
    [zico.widgets.io :as io]
    [zico.views.mon-trace-list]
    [zico.views.mon-trace-dist]
    [zico.views.mon-trace-tree]
    [zico.views.mon-trace-stats]
    [zico.views.common :as zv]
    [zico.widgets.screen :as zws]
    [secretary.core :as sc]))


;; Load basic dictionary data and initialize UI state


(zws/main-routes
  ; Monitoring screens
  "mon/trace/list"    #'zico.views.mon-trace-list/trace-list
  "mon/trace/tree"    #'zico.views.mon-trace-tree/trace-tree
  "mon/trace/dtree"   #'zico.views.mon-trace-dist/dtrace-tree
  "mon/trace/stats"   #'zico.views.mon-trace-stats/trace-stats
  )


(defn mount-root []
  (rc/render [zws/current-page] (.getElementById js/document "app")))

;; -------------------------
(defn init! []
  (ac/configure-navigation!
    {:nav-handler  (fn [path] (sc/dispatch! path))
     :path-exists? (fn [path] (sc/locate-route path))
     :reload-same-path? true})
  (ac/dispatch-current!)
  (mount-root))

