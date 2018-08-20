(ns zico.test-common
  (:require
    [re-frame.core :as rfc]
    [cljs.reader :refer [read-string]]
    [clojure.string :as cs]))

(enable-console-print!)

(defn setup-fixture []
  (rfc/dispatch-sync [:init-state])
  (rfc/dispatch [:xhr-init-mockup]))


(defn setup-io-mockup [id]
  (rfc/reg-event-db
    id
    (fn [db args] (assoc-in db [:test :io id] (cons (rest args) (get-in db [:test :io id]))))))

(doseq [id [:to-screen :alert :println :set-timeout]]
  (setup-io-mockup id))



; Simulated REST API



(def XHR-DATA-1
  {:cfg
   {:host
    {"21c00000-0501-0000-0001-0165413137c6"
     {:class :host,
      :uuid "21c00000-0501-0000-0001-0165413137c6"
      :app "21c00000-0201-0000-0002-0165413137b9",
      :env "21c00000-0301-0000-0006-000000000000",
      :name "waitapp.jdk8",
      :comment "AJ WAJ",
      :flags 1,
      :authkey "SkKgsbLiBQB4xqou"
      }}}})


(def RE-UUID #"[0-9a-f]{8}\-[0-9a-f]{4}\-[0-9a-f]{4}\-[0-9a-f]{4}\-[0-9a-f]{12}")


(defn url-split [url]
  (for [x (cs/split url #"/")
        :when (and (not= x "..") (not= x ""))]
    (if (re-matches RE-UUID x) x (keyword x))))


(defn dispatch-rec [path rec on-success]
  (if on-success
    {:dispatch (conj on-success (pr-str (if (keyword? (last path)) (vals rec) rec)))}))


(defn xhr-handler [{:keys [db]} [_ method url & {:keys [data on-success on-error content-type]}]]
  (let [path (url-split url)
        path (concat [:test :xhr] path)
        rec (get-in db path)]
    (case method
      :GET (merge {:db db} (dispatch-rec path rec on-success))
      :POST
      (let [uuid (str (random-uuid))
            data (assoc (read-string data) :uuid uuid),
            recs (get-in db path {})]
        (merge
          {:db (assoc-in recs path (assoc recs uuid data))}
          (if on-success {:dispatch (conj on-success (pr-str data))})))
      :PUT
      (let [uuid (last path), r (merge rec (read-string data)),
            recs (get-in db (butlast path))]
        (merge
          {:db (assoc-in db (butlast path) (assoc recs uuid r))}
          (if on-success {:dispatch (conj on-success (pr-str r))})))
      :DELETE
      (let [uuid (last path), recs (get-in db (butlast path))]
        (merge
          {:db (assoc-in db (butlast path) (dissoc recs uuid))}
          (if on-success {:dispatch (conj on-success ":ok")})))
      {:db db})))


(defn xhr-init [db _]
  (assoc-in db [:test :xhr] {:data XHR-DATA-1}))


(rfc/reg-event-db :xhr-init-mockup xhr-init)
(rfc/reg-event-fx :xhr xhr-handler)

