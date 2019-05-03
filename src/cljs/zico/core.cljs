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


(defn system-info-refresh []
  (zs/dispatch [:xhr/get (io/api "/system/info") [:data :user :about] nil
                :on-success [:system-info-check-timestamps]
                :on-error zv/DEFAULT-SERVER-ERROR]))


(defn system-info-check-timestamps-fx [{:keys [db]} [_ {:keys [tstamps] :as sysinfo}]]
  (let [tst0 (-> db :system :info :tstamps)]
    {:db (assoc-in db [:system :info] sysinfo)
     :dispatch-n
         (vec
           (for [k [:app :env :ttype :host] :let [v1 (k tst0), v2 (k tstamps)] :when (not= v1 v2)]
             [:data/refresh :cfg k]))}))


(zs/reg-event-fx :system-info-check-timestamps system-info-check-timestamps-fx)

(system-info-refresh)

(defonce
  system-info-timer
  (js/setInterval (fn [] (system-info-refresh)) 10000))     ; TODO this is stateful

(defn mount-root []
  (rc/render [zws/current-page zws/USER-INFO zv/DEFAULT-SERVER-ERROR]
             (.getElementById js/document "app")))

;; -------------------------
(defn init! []
  (ac/configure-navigation!
    {:nav-handler  (fn [path] (sc/dispatch! path))
     :path-exists? (fn [path] (sc/locate-route path))
     :reload-same-path? true})
  (ac/dispatch-current!)
  (mount-root))

