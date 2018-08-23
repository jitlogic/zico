(ns zico.forms
  (:require
    [zico.state :as zs]
    [zico.widgets :as zw]
    [zico.views.common :as zv]))

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


(defn form-edit-open-fx [{:keys [db]} [_ sectn view uuid fdefs]]
  {:db (let [obj (get-in db [:data sectn view uuid])]
         (assoc-in db [:view sectn view :edit] {:uuid uuid, :form (form-parse fdefs obj)}))
   :dispatch [:to-screen (str (name sectn) "/" (name view) "/edit")]})


(defn form-edit-new-fx [{:keys [db]} [_ sectn view nobj fdefs]]
  (let [nobj (assoc nobj :uuid :new)]
    {:db (-> db
             (assoc-in [:data sectn view :new] nobj)
             (assoc-in [:view sectn view :edit] {:uuid :new, :form (form-parse fdefs nobj)}))
     :dispatch [:to-screen (str (name sectn) "/" (name view) "/edit")]}))


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


(defn form-edit-cancel-fx [{:keys [db]} [_ sectn view]]
  {:db       (assoc-in db [:view sectn view :edit] nil)
   :dispatch [:to-screen (str (name sectn) "/" (name view) "/list")]})


(zs/reg-event-fx :form/edit-cancel form-edit-cancel-fx)
(zs/reg-event-fx :form/edit-new form-edit-new-fx)
(zs/reg-event-fx :form/edit-open form-edit-open-fx)
(zs/reg-event-db :form/set-text form-set-text-db)
(zs/reg-event-db :form/set-value form-set-value-db)
(zs/reg-event-fx :form/edit-commit form-edit-commit-fx)


; -------------------------- Basic widgets ----------------------

(defn render-form [sectn view title {:keys [fdefs]} {:keys [uuid] :as view-state}]
  (let [rnfn #(str (:name %) " - " (:comment %))]
    (fn []
      [:div.form-screen
       (for [{:keys [id attr widget type label rsub]} fdefs]
         ^{:key (or id attr)}
         [:div.form-row
          [:div.col1.label (str label ":")]
          [:div.col2
           (case widget                                     ; TODO pozbyć się tego case i wyeliminować pośrednią warstwę niżej - dodatkowe atrybuty do kontrolkek przekazywać bezpośrednio w fdefs
             :select
             [zw/select
              :path [:view sectn view :edit :form attr],
              :getter (zs/subscribe [:get [:view sectn view :edit :form attr]]),
              :setter [:form/set-text [:view sectn view :edit :form attr] (or type :nil)]
              :type (or type :nil), :rsub rsub, :rvfn :uuid, :rnfn rnfn]
             [zw/input
              :path [:view sectn view :edit :form attr],
              :getter (zs/subscribe [:get [:view sectn view :edit :form attr]]),
              :setter [:form/set-text [:view sectn view :edit :form attr] (or type :nil)]
              :type (or type :nil)])]])
       [:div.button-row
        [zw/button
         {:icon [:awe :ok :green], :text "Save",
          :on-click [:form/edit-commit sectn view fdefs
                     [:do [:data/refresh sectn view]
                      [:set [:view sectn view :selected] nil]]]}]
        [zw/button
         {:icon [:awe :cancel :red], :text "Cancel"
          :on-click [:form/edit-cancel sectn view]}]]]
      )))


(defn render-edit [sectn view title & {:keys [fdefs] :as params}]
  "Renders object editor screen. Editor allows for modifying configuration objects."
  (let [menu-open? (zs/subscribe [:get [:view :menu :open?]])
        view-state (zs/subscribe [:get [:view sectn view :edit]])]
    (fn []
      (let [view-state @view-state]
        [:div.top-container
         [zv/main-menu]
         [:div.main
          [:div.toolbar
           [(if @menu-open? :div.itm.display-none :div.itm)
            (zw/svg-button :awe :menu :text "Open menu" [:toggle [:view :menu :open?]])]
           [:div.flexible.flex.itm [:div.s " "]]
           [:div.flexible [:div.cpt title]]
           [:div.flexible.flex.itm
            (zw/svg-button :awe :ok :green "Save changes"
                           [:form/edit-commit sectn view fdefs
                            :on-refresh [(keyword "data" (str "cfg-" (name view) "-list"))]])
            (zw/svg-button :awe :cancel :red "Cancel editing" [:form/edit-cancel sectn view])]
           (zw/svg-button :awe :logout :text "User settings & logout"
                          [:toggle [:view :main :user-menu :open?]])
           ; TODO [zp/menu-popup "User menu" [:view :main :user-menu] :tr zv/USER-MENU-ITEMS]
           ]
          [:div.central-panel [render-form sectn view title params view-state]]
          ]]))))

