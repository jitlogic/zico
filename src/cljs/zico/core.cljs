(ns zico.core
  (:require
    [reagent.core :as rc]
    [zico.views.common :as zv]
    [zico.widgets.screen :as zws]
    [zico.views.mon-trace-list]
    [zico.views.mon-trace-dist]
    [zico.views.mon-trace-tree]
    [zico.views.mon-trace-stats]
    ))


;; Load basic dictionary data and initialize UI state

(defn mount-root []
  (rc/render [zws/current-screen nil zv/DEFAULT-SERVER-ERROR]
             (.getElementById js/document "app")))

;; -------------------------
(defn init! []
  (mount-root))

