(ns zico.forms
  (:require
    [zico.state :as zs]))

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



(defn form-set-value-db [db [_ path type value]]
  (let [text (value-unparse type value)
        db (assoc-in db (flatten (conj path :text)) text)]
    (assoc-in db (flatten (conj path :value)) value)))

(zs/reg-event-db :form/set-value form-set-value-db)


(defn form-set-text-db [db [_ path type text]]
  (let [value (value-parse type text)
        db (if value (assoc-in db (conj path :value) value) db)]
    (assoc-in db (conj path :text) text)))

(zs/reg-event-db :form/set-text form-set-text-db)


(defn form-edit-open-fx [{:keys [db]} [_ sectn view uuid fdefs]]
  {:db (let [obj (get-in db [:data sectn view uuid])]
         (assoc-in db [:view sectn view :edit] {:uuid uuid, :form (form-parse fdefs obj)}))
   :dispatch [:to-screen (str (name sectn) "/" (name view) "/edit")]})

(zs/reg-event-fx :form/edit-open form-edit-open-fx)


(defn form-edit-new-fx [{:keys [db]} [_ sectn view nobj fdefs]]
  (let [nobj (assoc nobj :uuid :new)]
    {:db (-> db
             (assoc-in [:data sectn view :new] nobj)
             (assoc-in [:view sectn view :edit] {:uuid :new, :form (form-parse fdefs nobj)}))
     :dispatch [:to-screen (str (name sectn) "/" (name view) "/edit")]}))

(zs/reg-event-fx :form/edit-new form-edit-new-fx)


(defn form-edit-commit-fx [{:keys [db]} [_ sectn view fdefs on-refresh]]
  (let [{:keys [uuid form]} (get-in db [:view sectn view :edit])
        orig (get-in db [:data sectn view uuid])
        newv (merge orig (form-unparse fdefs form))]
    (merge
      {:db         (-> db (assoc-in [:data sectn view uuid] newv) (assoc-in [:view sectn view :edit] nil))
       :dispatch-n [[:to-screen (str (name sectn) "/" (name view) "/list")]
                    (if (= :new uuid)
                      [:xhr/post (str "../../../data/" (name sectn) "/" (name view)) nil (dissoc newv :uuid)
                       :on-success on-refresh
                       :on-error [:dissoc [:data sectn view] :new]]
                      [:xhr/put (str "../../../data/" (name sectn) "/" (name view) "/" uuid) nil newv
                       ; TODO move this to dedicated module :on-error zv/DEFAULT-SERVER-ERROR
                       ]
                      )]})))

(zs/reg-event-fx :form/edit-commit form-edit-commit-fx)


(defn form-edit-cancel-fx [{:keys [db]} [_ sectn view]]
  {:db       (assoc-in db [:view sectn view :edit] nil)
   :dispatch [:to-screen (str (name sectn) "/" (name view) "/list")]})

(zs/reg-event-fx :form/edit-cancel form-edit-cancel-fx)

