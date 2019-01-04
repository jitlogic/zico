(ns zico.widgets.forms
  (:require-macros
    [reagent.ratom :as ra])
  (:require
    [zico.widgets.state :as zs]
    [zico.widgets.widgets :as zw]
    [zico.widgets.io :as io]))

; ----------- Parsing and unparsing form data --------------

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



; ------------- Basic event handlers for forms -------------


(defn form-set-value-db [db [_ path type value]]
  (let [text (value-unparse type value)
        db (assoc-in db (flatten (conj path :text)) text)]
    (assoc-in db (flatten (conj path :value)) value)))


(defn form-set-text-db [db [_ path type text]]
  (let [value (value-parse type text)
        db (if value (assoc-in db (conj path :value) value) db)]
    (assoc-in db (conj path :text) text)))


(defn form-edit-open-fx [{:keys [db]} [_ vpath dpath url id fdefs]]
  {:db (let [obj (get-in db (concat dpath [id]))]
         (assoc-in db (concat vpath [:edit]) {:id id, :form (form-parse fdefs obj)}))
   :dispatch [:to-screen url]})


(defn form-edit-new-fx [{:keys [db]} [_ vpath dpath url nobj fdefs]]
  (let [nobj (assoc nobj :id :new)]
    {:db (-> db
             (assoc-in (concat dpath [:new]) nobj)
             (assoc-in (concat vpath [:edit]) {:id :new, :form (form-parse fdefs nobj)}))
     :dispatch [:to-screen url]}))


(defn form-edit-commit-fx [{:keys [db]} [_ vpath dpath url xhr-url fdefs on-refresh]]
  (let [{:keys [id form]} (get-in db (concat vpath [:edit]))
        orig (get-in db (concat dpath [id]))
        newv (merge orig (form-unparse fdefs form))]
    (merge
      {:db (-> db
               (assoc-in (concat dpath [id]) newv)
               (assoc-in (concat vpath [:edit]) nil))
       :dispatch-n [[:to-screen url]
                    (if (= :new id)
                      [:xhr/post xhr-url nil (dissoc newv :id)
                       :on-success on-refresh
                       :on-error [:dissoc dpath :new]]
                      [:xhr/put (str xhr-url "/" id) nil newv])]})))


(defn form-edit-cancel-fx [{:keys [db]} [_  vpath dpath url]]
  {:db       (-> db
                 (assoc-in (concat vpath [:edit]) nil)
                 (assoc-in dpath (dissoc (get-in db dpath) :new)))
   :dispatch [:to-screen url]})


(defn zorka-refresh-data [{:keys [db]} [_ sectn view]]
  {:db db
   :dispatch
       [:xhr/get (io/api "/" (name sectn) "/" (name view))
        [:data sectn view] nil,
        :map-by :id]})


(zs/reg-event-fx :data/refresh zorka-refresh-data)

(zs/reg-event-fx :form/edit-cancel form-edit-cancel-fx)
(zs/reg-event-fx :form/edit-new form-edit-new-fx)
(zs/reg-event-fx :form/edit-open form-edit-open-fx)
(zs/reg-event-db :form/set-text form-set-text-db)
(zs/reg-event-db :form/set-value form-set-value-db)
(zs/reg-event-fx :form/edit-commit form-edit-commit-fx)


; -------------------------- Basic widgets ----------------------

(defmulti
  render-widget
  "Renders form widget. Uses generic widget library."
  (fn [_ fdef] (:widget fdef :input)))


(defmethod render-widget :select [vpath {:keys [attr type ds-getter valid? ds-name-fn]}]
  (let [vpa (vec (concat vpath [:edit :form attr]))]
    [zw/select
     :getter (zs/subscribe [:get (concat vpa [:text])]),
     :setter [:form/set-text vpa (or type :nil)],
     :type (or type :nil), :valid? (or valid? true),
     :ds-getter ds-getter, :ds-val-fn :id, :ds-name-fn ds-name-fn]))


(defmethod render-widget :input [vpath {:keys [attr type valid?]}]
  (let [vpa (vec (concat vpath [:edit :form attr]))]
    [zw/input
     :getter (zs/subscribe [:get (concat vpa [:text])]),
     :setter [:form/set-text vpa (or type :nil)],
     :type (or type :nil), :valid? (or valid? true)]))


; ---------------------------- Form rendering --------------------------

(defn render-form [&{:keys [vpath dpath url xhr-url fdefs form-valid?]}]
  (let []
    (fn []
      [:div.form-screen
       (for [{:keys [id attr label] :as fdef} fdefs]
         ^{:key (or id attr)}
         [:div.form-row
          [:div.col1.label (str label ":")]
          [:div.col2
           (if (= fdef (first fdefs))
             [zw/autofocus (render-widget vpath fdef)]
             (render-widget vpath fdef))]])
       [:div.button-row
        [zw/button
         :icon [:awe :ok :green], :text "Save", :enabled? form-valid?,
         :on-click [:form/edit-commit vpath dpath (str url "/list") xhr-url fdefs
                    [:set (concat vpath [:selected]) nil]]]
        [zw/button
         :icon     [:awe :cancel :red], :text "Cancel"
         :on-click [:form/edit-cancel vpath dpath (str url "/list")]]]])))


(defn validator [vpath field vfn]
  (let [text (zs/subscribe [:get (concat vpath [:edit :form field :text])])]
    (ra/reaction
      (vfn @text))))


(defn text-not-empty-vfn [v]
  (not (empty? v)))


(defn option-selected-vfn [v]
  (not (or (empty? v) (= v "-"))))


(defn validated-fdefs [vpath & fdefs]
  "Applies default set of validators."
  (for [{:keys [attr widget valid?] :as f} fdefs]
    (cond
      (some? valid?) f
      (= :select widget) (assoc f :valid? (validator vpath attr option-selected-vfn))
      :else (assoc f :valid? (validator vpath attr text-not-empty-vfn)))))

