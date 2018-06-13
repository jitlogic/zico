(ns zico.state
  (:require-macros
    [reagent.ratom :as ra])
  (:require
    [goog.events :as ge]
    [clojure.string :as cs]
    [re-frame.core :as rfc]
    [cljs.reader :refer [read-string]]
    [zico.util :refer [deref?]])
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


; Processing values for form controls

; TODO usunąć parametr db z value-parse i unparse !!!

(defn value-type-switch [type _]
  (cond
    (map? type) :map
    :else type))

(defmulti value-parse
          "Converts form produced by HTML controls (mainly string) to raw data type;"
          value-type-switch)

(defmethod value-parse :nil [_ text]
  text)

(defmethod value-parse :string [_ text]
  text)

(defmethod value-parse :int [_ text]
  (when (and text (re-matches #"\d+" text)) (js/parseInt text)))

(defmethod value-parse :map [m text]
  (first (for [[k v] m :when (= v text)] k)))


(defmulti value-unparse
          "Converts raw data to form consumable by HTML controls (mainly string)"
          value-type-switch)

(defmethod value-unparse :nil [_ value]
  value)

(defmethod value-unparse :string [_ value]
  (str value))

(defmethod value-unparse :int [_ value]
  (str value))

(defmethod value-unparse :map [m value]
  (get m value))


(defn form-parse [fdefs rdata]
  (into {} (for [{:keys [attr type]} fdefs :let [v (get rdata attr)]]
             {attr {:value v, :text (value-unparse (or type :nil) v)}})))


(defn form-unparse [fdefs fdata]
  (into {} (for [{:keys [attr type]} fdefs]
             {attr (get-in fdata [attr :value])})))


; ---------------------------- A bunch of generic handlers ----------------------------

(reg-event-db
  :println
  (fn println-fn [db [_ & args]]
    (println (str args))
    db))


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


(reg-event-db
  :alert
  (fn alert-handler [db [_ msg]]
    (js/alert msg)
    db))


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


(reg-event-db
  :form/set-value
  (fn set-value-fn [db [_ path type value]]
    (let [text (value-unparse type value)
          db (assoc-in db (flatten (conj path :text)) text)]
      (assoc-in db (flatten (conj path :value)) value))))


(reg-event-db
  :form/set-text
  (fn set-text-fn [db [_ path type text]]
    (let [value (value-parse type text)
          db (if value (assoc-in db (conj path :value) value) db)]
      (assoc-in db (conj path :text) text))))


(reg-event-fx
  :form/edit-open
  (fn [{:keys [db]} [_ sectn view uuid fdefs]]
    {:db (let [obj (get-in db [:data sectn view uuid])]
           (assoc-in db [:view sectn view :edit] {:uuid uuid, :form (form-parse fdefs obj)}))
     :dispatch [:to-screen (str (name sectn) "/" (name view) "/edit")]}))


(reg-event-fx
  :form/edit-new
  (fn [{:keys [db]} [_ sectn view nobj fdefs]]
    (let [nobj (assoc nobj :uuid :new)]
      {:db (-> db
               (assoc-in [:data sectn view :new] nobj)
               (assoc-in [:view sectn view :edit] {:uuid :new, :form (form-parse fdefs nobj)}))
       :dispatch [:to-screen (str (name sectn) "/" (name view) "/edit")]})))


(reg-event-fx
  :form/edit-commit
  (fn [{:keys [db]} [_ sectn view fdefs on-refresh]]
    (let [{:keys [uuid form]} (get-in db [:view sectn view :edit])
          orig (get-in db [:data sectn view uuid])
          newv (merge orig (form-unparse fdefs form))]
      (merge
        {:db         (-> db (assoc-in [:data sectn view uuid] newv) (assoc-in [:view sectn view :edit] nil))
         :dispatch-n [[:to-screen (str (name sectn) "/" (name view) "/list")]
                      (if (= :new uuid)
                        [:rest/post (str "../../../data/" (name sectn) "/" (name view)) (dissoc newv :uuid)
                         :on-success on-refresh
                         :on-error [:dissoc [:data sectn view] :new]]
                        [:rest/put (str "../../../data/" (name sectn) "/" (name view) "/" uuid) newv]
                        )]}))))


(reg-event-fx
  :form/edit-cancel
  (fn [{:keys [db]} [_ sectn view]]
    {:db       (assoc-in db [:view sectn view :edit] nil)
     :dispatch [:to-screen (str (name sectn) "/" (name view) "/list")]}))


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


(reg-event-db
  :debug
  (fn debug-handler [db v]
    (println "DEBUG: " (str v))
    db))

; TODO get rid of this here, move somewhere to application code
(reg-event-db
  :logout
  (fn logout-handler [db [_]]
    (set! (.-location js/window) "/logout")
    db))


; ---------------------------- A bunch of generic subscriptions -----------------------

(register-sub
  :get
  (fn [db [_ path]]
    (ra/reaction (get-in @db path))))


(register-sub
  :sort-by
  (fn [db [_ path sort-fn]]
    (let [data (ra/reaction (get-in @db path))]
      (ra/reaction (sort-by (deref? sort-fn) @data)))))


(register-sub
  :form/get-value
  (fn [db [_ path]]
    (ra/reaction (get-in @db (conj path :value)))))


; ------------------ Timer handing and deferred actions  -------------------------------

; Starts or updates timer
(reg-event-fx
  :timer/update
  (fn [{:keys [db]} [_ path timeout action]]
    (let [t0 (+ (.getTime (js/Date.)) timeout)
          {t1 :timeout :as trec} (get-in db path)]
      (if (some? t1)
        {:db (assoc-in db path (assoc trec :timeout (max t0 t1) :action action))}
        (do
          (js/setTimeout (fn [] (dispatch [:timer/tick path])) timeout)
          {:db (assoc-in db path (assoc trec :timeout t0, :action action))}))
      )))


(reg-event-fx
  :timer/tick
  (fn [{:keys [db]} [_ path]]
    (let [t (.getTime (js/Date.)),
          {:keys [timeout action] :as trec} (get-in db path)]
      (cond
        (nil? timeout) {:db db}
        (<= timeout t) {:db (assoc-in db path (dissoc trec :timeout :action)) :dispatch action}
        :else
        (do
          (js/setTimeout (fn [] (dispatch [:timer/tick path])) (- timeout t))
          {:db db})))))


(reg-event-fx
  :timer/flush
  (fn [{:keys [db]} [_ path]]
    (let [{:keys [action] :as trec} (get-in db path {})]
      (merge
        {:db (assoc-in db path (dissoc trec :timeout :action))}
        (when action {:dispatch action})))))


(reg-event-db
  :timer/cancel
  (fn [db [_ path]]
    (let [trec (get-in db path {})]
      (assoc-in db path (dissoc trec :timeout :action)))))

; ------------------------------- REST I/O  ------------------------------------

(def ^:private crud-methods {:GET "GET" :PUT "PUT" :POST "POST" :DELETE "DELETE"})

(defn xhr [method url & {:keys [data on-success on-error]}]
  (let [xhr (XhrIo.)]
    (when on-success
      (ge/listen xhr goog.net.EventType.SUCCESS
                 (fn [_] (on-success (read-string (.getResponseText xhr))))))
    (when on-error
      (ge/listen xhr goog.net.EventType.ERROR
                 (fn [_] (on-error {:msg (.getResponseText xhr)}))))
    (.send  xhr  url (crud-methods method) (when data (pr-str data))
            #js {"Content-Type" "application/edn; charset=utf-8"
                 "Accept" "application/edn; charset=utf-8"})))


(defmulti rest-process (fn [by _] by))


(reg-event-db
  :rest/get
  (fn get-rest-data [db [_ url dest-path & {:keys [on-success map-by proc-by merge-by]}]]
    (xhr
      :GET url,
      :on-success
      (fn [vs]
        (let [vp (cond
                   map-by (into {} (for [v vs] [(map-by v) v]))
                   proc-by (rest-process proc-by vs)
                   :else vs)]
          (dispatch
            [:set dest-path (if merge-by (merge-by (get-in db dest-path) vp) vp)]))
        (when on-success (dispatch (conj on-success vs)))))
    db))


(reg-event-db
  :rest/post
  (fn set-rest-data [db [_ url rec & {:keys [on-success on-error]}]]
    (xhr :POST url, :data rec,
         :on-success #(when on-success (dispatch (conj on-success %)))
         :on-error #(when on-error (dispatch (conj on-error %))))
    db))


(reg-event-db
  :rest/put
  (fn set-rest-data [db [_ url rec & {:keys [on-success on-error]}]]
    (xhr :PUT url, :data rec,
         :on-success #(when on-success (dispatch (conj on-success %)))
         :on-error #(when on-error (dispatch (conj on-error %))))
    db))


(reg-event-db
  :rest/delete
  (fn delete-rest-data [db [_ url & {:keys [on-success on-error]}]]
    (xhr :DELETE url, :data nil,
         :on-success #(when on-success (dispatch (conj on-success %)))
         :on-error #(when on-error (dispatch (conj on-error %))))
    db))


(reg-event-db
  :update-record
  (fn commit-form-data [db [_ dpath idattr id rec]]
    (assoc-in
      db dpath
      (for [r (get-in db dpath)]
        (if (= (idattr rec) (idattr r)) (merge r rec) r)))))


; --------------------------- REST Data interface functions specific to zorka ------------------------

; TODO get rid of this, move to application code
(defn data-list-sfn [sectn view sort-attr]
  (fn [db [_]]
    (let [data (ra/reaction (get-in @db [:data sectn view]))
          srev (ra/reaction (get-in @db [:view sectn view :sort :rev]))]
      (ra/reaction
        (let [rfn (if @srev reverse identity)]
          (rfn (sort-by sort-attr (vals @data)))))
      )))

; TODO get rid of this, move to application code
(reg-event-fx
  :data/refresh
  (fn zorka-refresh-data [{:keys [db]} [_ sectn view]]
    {:db db
     :dispatch
     [:rest/get (str "../../../data/" (name sectn) "/" (name view))
      [:data sectn view] :map-by :uuid]}))

