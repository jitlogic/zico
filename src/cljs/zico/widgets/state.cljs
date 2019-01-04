(ns zico.widgets.state
  (:require-macros
    [reagent.ratom :as ra])
  (:require
    [clojure.string :as cs]
    [re-frame.core :as rfc]
    [cljs.reader :refer [read-string]]
    [zico.widgets.util :as zwu])
  (:import
    goog.net.XhrIo
    goog.net.EventType
    [goog.events EventType]))


(def dispatch rfc/dispatch)
(def dispatch-sync rfc/dispatch-sync)
(def subscribe rfc/subscribe)
(def reg-event-db rfc/reg-event-db)
(def register-sub rfc/reg-sub-raw)

(def reg-event-fx rfc/reg-event-fx)
(def reg-fx rfc/reg-fx)

(def initial-state
  {:user {}, :data {}, :view {}, :system {}})


(defn to-handler [code & {:keys [sink]}]                    ; TODO simplify this thing
  (let [sink (if sink sink (= :sink (first code)))
        code (if (= :sink (first code)) (vec (rest code)) code)
        ret (if sink false nil)]
    (cond
      (vector? code)
      (cond
        (keyword? (first code)) (fn [e] (when sink (.stopPropagation e)) (dispatch code) ret)
        (vector? (first code)) (fn [e] (when sink (.stopPropagation e)) (doseq [c code] (dispatch c)) ret)
        :else (dispatch [:println "Illegal handler code: " code]))
      (fn? code) code
      (nil? code) nil
      :else (constantly nil))))


(defn traverse-and-handle [node attr tid & {:as handlers}]
  "Traverses up DOM tree, looks for given attribute and if found, executes handler depending
  on first found widget in bubbling sequence."
  (loop [node node, handler nil]
    (let [n (.-nodeName node), i (.getAttribute node "id"), p (.-parentNode node)
          v (.getAttribute node attr), c (.getAttribute node "class"),
          h (first (for [s (cs/split c #"[ ]+"), :let [h (handlers (keyword s))], :when h] h))
          handler (or handler h)]
      (cond
        (and (some? v) h) (handler v)
        (or (nil? p) (= "HTML" n) (= tid i)) nil
        :else (recur p (or handler h))))))


(reg-event-db
  :init-state
  (fn init-state-fn [db [_ _ {:keys [reset?]}]]
    (if (or (empty? db) reset?)
      (merge db initial-state)
      db)))

(dispatch-sync [:init-state nil :reset? false])

; ---------------------------- A bunch of generic handlers ----------------------------


; No-op handler. Does nothing.
(reg-event-db
  :nop
  (fn [db & _]
    db))


; Simple wrapper for :sink argument in to-handler function;
(reg-event-fx
  :sink
  (fn [{:keys [db]} [_ & ev]]
    {:db db,
     :dispatch (vec ev)}))


; Groups multiple handlers into one
(reg-event-fx
  :do
  (fn [{:keys [db]} [_ & msgs]]
    {:db db,
     :dispatch-n (for [msg msgs :when (vector? msg)] msg)}))


(reg-event-db
  :set
  (fn set-data-fn [db [_ path data & {:keys [parse]}]]
    (assoc-in
      db path
      (cond
        (= parse :edn) (read-string data)
        :else data))))


(reg-event-db
  :dissoc
  (fn [db [_ path key]]
    (assoc-in db path (dissoc (get-in db path) key))))


(reg-event-db
  :toggle
  (fn toggle-data-fn [db [_ path val]]
    (if val
      (assoc-in db path (if (= val (get-in db path)) nil val))
      (assoc-in db path (not (get-in db path))))))



(defonce once-events (atom {}))


(reg-event-fx
  :once
  (fn [{:keys [db]} [_ & ev]]
    (let [ev (if (vector? (first ev)) (first ev) (vec ev))]
      (merge
        {:db db}
        (when-not (get @once-events ev)
          (swap! once-events assoc ev 1)
          {:dispatch ev})))))

; -------------------------------- Dispatch event stack ----------------------------------
; Can be useful for implementing event history inependent of browser history mechanism

(reg-event-fx
  :event/push-dispatch
  (fn [{:keys [db]} [_ path & events]]
    {:db (assoc-in db path (cons events (get-in db path)))
     :dispatch-n events}))


(reg-event-fx
  :event/pop-dispatch
  (fn [{:keys [db]} [_ path & defev]]
    (let [stack (rest (get-in db path))
          db (if stack (assoc-in db path stack) db)]
      (cond
        (first stack) {:db db, :dispatch-n (first stack)}
        defev {:db db, :dispatch-n defev}
        :else {:db db}))))

; ---------------------------- A bunch of generic subscriptions -----------------------

(register-sub
  :get
  (fn [db [_ path]]
    (ra/reaction (get-in @db path))))


(register-sub
  :sort-by
  (fn [db [_ path sort-fn]]
    (let [data (ra/reaction (get-in @db path))]
      (ra/reaction (sort-by (zwu/deref? sort-fn) @data)))))



; ------------------ Timer handing and deferred actions  -------------------------------

(defn timer-update-handler [{:keys [db]} [_ path timeout ctime action]]
  (let [t0 (+ (or ctime (.getTime (js/Date.))) timeout)
        {t1 :timeout :as trec} (get-in db path)]
    (if (some? t1)
      {:db (assoc-in db path (assoc trec :timeout (max t0 t1) :action action))}
      {:db       (assoc-in db path (assoc trec :timeout t0, :action action))
       :dispatch [:set-timeout timeout [:timer/tick path nil]]})))

(defn timer-tick-handler [{:keys [db]} [_ path ctime]]
  (let [t (or ctime (.getTime (js/Date.))),
        {:keys [timeout action] :as trec} (get-in db path)]
    (cond
      (nil? timeout) {:db db}
      (<= timeout t) {:db (assoc-in db path (dissoc trec :timeout :action)), :dispatch action}
      :else
      {:db db, :dispatch [:set-timeout (- timeout t) [:timer/tick path nil]]})))

(defn timer-flush-handler [{:keys [db]} [_ path]]
  (let [{:keys [action] :as trec} (get-in db path {})]
    (merge
      {:db (assoc-in db path (dissoc trec :timeout :action))}
      (when action {:dispatch action}))))

(defn timer-cancel-handler [db [_ path]]
  (let [trec (get-in db path {})]
    (assoc-in db path (dissoc trec :timeout :action))))

; Starts or updates timer
(reg-event-fx :timer/update timer-update-handler)
(reg-event-fx :timer/tick timer-tick-handler)
(reg-event-fx :timer/flush timer-flush-handler)
(reg-event-db :timer/cancel timer-cancel-handler)

; ------------------------------- XHR I/O  ------------------------------------

(defmulti rest-process (fn [by _] by))

(defn xhr-success-handler [{:keys [db]} [_ dest-path {:keys [on-success map-by proc-by merge-by]} data]]
  (let [vs (read-string data)
        vp (cond
             map-by (into {} (for [v vs] [(map-by v) v]))
             proc-by (rest-process proc-by vs)
             :else vs)
        vm (if merge-by (merge-by (get-in db dest-path) vp) vp)]
    (merge
      {:db (assoc-in db dest-path vm)}
      (if on-success {:dispatch (conj on-success vm)}))))


(defn xhr-error-handler [{:keys [db]} [_ _ {:keys [on-error]} data]]
  (merge
    {:db db}
    (if on-error {:dispatch (conj on-error data)})))


(defn xhr-handler-fn [method]
  (fn [{:keys [db]} [_ url dest-path post-data & {:as opts}]]
    (let [data (when post-data (pr-str post-data))]
      {:db       db
       :dispatch [:xhr method url, :data data,
                  :on-success [:xhr/success dest-path opts]
                  :on-error [:xhr/error dest-path opts]
                  :content-type "application/edn; charset=utf-8"]})))


(rfc/reg-event-fx :xhr/get (xhr-handler-fn :GET))
(rfc/reg-event-fx :xhr/post (xhr-handler-fn :POST))
(rfc/reg-event-fx :xhr/put (xhr-handler-fn :PUT))
(rfc/reg-event-fx :xhr/delete (xhr-handler-fn :DELETE))

(rfc/reg-event-fx :xhr/success xhr-success-handler)
(rfc/reg-event-fx :xhr/error xhr-error-handler)

