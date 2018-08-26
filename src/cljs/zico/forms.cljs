(ns zico.forms
  (:require-macros
    [reagent.ratom :as ra])
  (:require
    [zico.state :as zs]
    [zico.widgets :as zw]
    [zico.views.common :as zv]
    [zico.util :as zu]))

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


(defn form-edit-open-fx [{:keys [db]} [_ vpath dpath url uuid fdefs]]
  {:db (let [obj (get-in db (concat dpath [uuid]))]
         (assoc-in db (concat vpath [:edit]) {:uuid uuid, :form (form-parse fdefs obj)}))
   :dispatch [:to-screen url]})


(defn form-edit-new-fx [{:keys [db]} [_ vpath dpath url nobj fdefs]]
  (let [nobj (assoc nobj :uuid :new)]
    {:db (-> db
             (assoc-in (concat dpath [:new]) nobj)
             (assoc-in (concat vpath [:edit]) {:uuid :new, :form (form-parse fdefs nobj)}))
     :dispatch [:to-screen url]}))


(defn form-edit-commit-fx [{:keys [db]} [_ vpath dpath url xhr-url fdefs on-refresh]]
  (let [{:keys [uuid form]} (get-in db (concat vpath [:edit]))
        orig (get-in db (concat dpath [uuid]))
        newv (merge orig (form-unparse fdefs form))]
    (merge
      {:db (-> db
               (assoc-in (concat dpath [uuid]) newv)
               (assoc-in (concat vpath [:edit]) nil))
       :dispatch-n [[:to-screen url]
                    (if (= :new uuid)
                      [:xhr/post xhr-url nil (dissoc newv :uuid)
                       :on-success on-refresh
                       :on-error [:dissoc dpath :new]]
                      [:xhr/put (str xhr-url "/" uuid) nil newv])]})))


(defn form-edit-cancel-fx [{:keys [db]} [_  vpath dpath url]]
  {:db       (-> db
                 (assoc-in (concat vpath [:edit]) nil)
                 (assoc-in dpath (dissoc (get-in db dpath) :new)))
   :dispatch [:to-screen url]})


(defn zorka-refresh-data [{:keys [db]} [_ sectn view]]
  {:db db
   :dispatch
       [:xhr/get (str "../../../data/" (name sectn) "/" (name view))
        [:data sectn view] nil,
        :map-by :uuid]})


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


(defmethod render-widget :select [vpath {:keys [attr type rsub valid?]}]
  (let [rnfn #(str (:name %) " - " (:comment %))
        vpa (vec (concat vpath [:edit :form attr]))]
    [zw/select
     :getter (zs/subscribe [:get (concat vpa [:text])]),
     :setter [:form/set-text vpa (or type :nil)],
     :type (or type :nil), :rsub rsub, :rvfn :uuid, :rnfn rnfn, :valid? (or valid? true)]))


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


(defn render-edit [& {:keys [vpath dpath title fdefs url xhr-url on-refresh]}]
  "Renders object editor screen. Editor allows for modifying configuration objects."
  (let [menu-open? (zs/subscribe [:get [:view :menu :open?]])
        form-valid? (ra/reaction
                      (let [vs (filter some? (map zu/deref? (map :valid? fdefs)))]
                        (empty? (for [v vs :when (not v)] v))))]
    (fn []
      [:div.top-container
       [zv/main-menu]
       [:div.main
        [:div.toolbar
         [(if @menu-open? :div.itm.display-none :div.itm)
          (zw/svg-button :awe :menu :text "Open menu" [:toggle [:view :menu :open?]])]
         [:div.flexible.flex.itm [:div.s " "]]
         [:div.flexible [:div.cpt title]]
         [:div.flexible.flex.itm
          (zw/svg-button
            :awe :ok :green "Save changes"
            [:form/edit-commit vpath dpath (str url "/list") xhr-url fdefs :on-refresh on-refresh]
            :enabled? form-valid?)
          (zw/svg-button
            :awe :cancel :red "Cancel editing"
            [:form/edit-cancel vpath dpath (str url "/list")])]
         (zw/svg-button :awe :logout :text "User settings & logout"
                        [:toggle [:view :main :user-menu :open?]])]
        [:div.central-panel
         [render-form :vpath vpath, :dpath dpath, :url url, :xhr-url xhr-url,
          :title title, :form-valid? form-valid?, :fdefs fdefs]]
        ]])))


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




(defn render-item [&{:keys [vpath list-col1 list-col2] :or {list-col1 :name, list-col2 :comment}}]
  (fn [{:keys [uuid] :as obj}]
    ^{:key uuid}
    [:div.itm {:on-click (zs/to-handler [:toggle (concat vpath [:selected]) uuid])}
     [:div.c1.dsc [:div.n (list-col1 obj)]]
     [:div.c2.cmt (list-col2 obj)]]))


(defn render-detail [&{:keys [vpath dpath fdefs url xhr-url]}]
  (fn [{:keys [uuid name comment] :as obj}]
    ^{:key uuid}
    [:div.det {:on-click (zs/to-handler [:toggle (concat vpath [:selected]) uuid])}
     [:div.dsc
      [:div.kv
       [:div.c1.k "Name:"]
       [:div.c2.v name]
       [:div.c2.c.hide-s comment]]]
     [:div.btns
      [:div.ellipsis.c-gray uuid]
      (zw/svg-button
        :awe :clone :text "Clone item"
        [:form/edit-new vpath dpath
         (str url "/edit") obj fdefs])
      (zw/svg-button
        :awe :trash :red "Delete item"
        [:popup/open :msgbox,
         :caption "Deleting object", :modal? true,
         :text (str "Delete object: " name " ?"),
         :buttons
         [{:id :ok, :text "Delete", :icon [:awe :ok :green]
           :on-click [:xhr/delete
                      (str xhr-url "/" uuid) nil nil
                      :on-success [:dissoc dpath uuid]
                      :on-error zv/DEFAULT-SERVER-ERROR]}
          {:id :cancel, :text "Cancel", :icon [:awe :cancel :red]}]])
      (zw/svg-button
        :awe :edit :text "Edit item"
        [:form/edit-open vpath dpath
         (str url "/edit") uuid fdefs])
      ]]))


(defn render-list [&{:keys [vpath dpath data class fdefs url xhr-url template title on-refresh]}]
  (let [list-col1 (last (cons :name (for [fd fdefs :when (:list-col1 fd)] (:attr fd))))
        list-col2 (last (cons :comment (for [fd fdefs :when (:list-col2 fd)] (:attr fd))))]
    (fn [_]
      (when on-refresh
        (zs/dispatch (vec (concat [:once] on-refresh))))
      (zv/render-screen
        :toolbar [zv/list-screen-toolbar
                  :vpath vpath
                  :title title,
                  :on-refresh on-refresh,
                  :add-left (when template
                              [:div (zw/svg-button
                                      :awe :plus :green "New"
                                      [:form/edit-new vpath dpath
                                       (str url "/edit") template fdefs])])]
        :central [zv/list-interior :vpath vpath, :data data, :class class
                  :render-item (render-item :vpath vpath, :list-col1 list-col1, :list-col2 list-col2)
                  :render-details (render-detail :vpath vpath, :dpath dpath, :url url, :xhr-url xhr-url, :fdefs fdefs)
                  ]))))

