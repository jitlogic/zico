(ns zico.views.cfg
  (:require
    [reagent.ratom :as ra]
    [zico.widgets.state :as zs]
    [zico.widgets.forms :as zf]
    [zico.widgets.widgets :as zw]
    [zico.views.common :as zv]
    [zico.widgets.io :as io]
    [zico.widgets.util :as zu]
    [zico.widgets.screen :as zws]))

(defn data-list-sfn [sectn view sort-attr]
  (fn [db [_]]
    (let [data (ra/reaction (get-in @db [:data sectn view]))
          srev (ra/reaction (get-in @db [:view sectn view :sort :rev]))]
      (ra/reaction
        (let [rfn (if @srev reverse identity)]
          (rfn (sort-by sort-attr (vals @data)))))
      )))

; Register all needed subscriptions
(doseq [k [:env :app :host :user :ttype :hostreg]]
  (zs/register-sub
    (keyword "data" (str "cfg-" (name k) "-list"))
    (data-list-sfn :cfg k :name)))


(defn render-btns [{:keys [vpath dpath fdefs url xhr-url] :as cfg}
                   {:keys [id name] :as obj}]
  [:div.btns
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
                   (str xhr-url "/" id) nil nil
                   :on-success [:dissoc dpath id]
                   :on-error zv/DEFAULT-SERVER-ERROR]}
       {:id :cancel, :text "Cancel", :icon [:awe :cancel :red]}]])
   (zw/svg-button
     :awe :edit :text "Edit item"
     [:form/edit-open vpath dpath
      (str url "/edit") id fdefs])])


(defn resolve-ref [rsub id]
  (let [data @(zs/subscribe rsub)]
    (first (for [d data :when (= id (:id d))] (:name d)))))


(defn render-item [detail? &{:keys [vpath dpath fdefs url list-col1 list-col2 xhr-url]
                             :or {list-col1 :name, list-col2 :comment} :as cfg}]
  (fn [{:keys [id glyph] :as obj}]
    ^{:key id}
    [(if detail? :div.hl :div)
     [:div.itm
      {:on-click (zs/to-handler [:toggle (concat vpath [:selected]) id])}
      (when glyph
        (let [[f i c] (zu/glyph-parse glyph glyph)]
          [:div.ci (zw/svg-icon f i c)]))
      [:div.c1.dsc [:div.n (list-col1 obj)]]
      [:div.c2.cmt (list-col2 obj)]
      (if (zws/has-role :admin)
        (render-btns cfg obj))]
     (when detail?
       [:div.kvl
        (doall
          (for [{:keys [attr label ds-getter] :as fdef} fdefs
                :when (not= attr :glyph)]
            ^{:key attr}
            [:div.kv
             [:div.k label]
             [:div.v
              (if ds-getter
                (resolve-ref ds-getter (attr obj))
                (attr obj))]]))])]))


(defn render-list [&{:keys [vpath dpath data class fdefs url xhr-url template title on-refresh]}]
  (let [list-col1 (last (cons :name (for [fd fdefs :when (:list-col1 fd)] (:attr fd))))
        list-col2 (last (cons :comment (for [fd fdefs :when (:list-col2 fd)] (:attr fd))))]
    (fn [_]
      (when on-refresh
        (zs/dispatch (vec (concat [:once] on-refresh))))
      (zws/render-screen
        :main-menu zv/main-menu
        :user-menu zv/USER-MENU
        :toolbar [zws/list-screen-toolbar
                  :vpath vpath
                  :title title,
                  :on-refresh on-refresh,
                  :add-left (when (and template (zws/has-role :admin))
                              [:div (zw/svg-button
                                      :awe :plus :green "New"
                                      [:form/edit-new vpath dpath
                                       (str url "/edit") template fdefs])])]
        :central [zws/list-interior :vpath vpath, :data data, :class class
                  :render-item (render-item false :vpath vpath, :dpath dpath, :list-col1 list-col1, :list-col2 list-col2, :xhr-url xhr-url, :url url)
                  :render-details (render-item true :vpath vpath, :dpath dpath, :url url, :xhr-url xhr-url, :fdefs fdefs)
                  ]))))


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
         [zf/render-form :vpath vpath, :dpath dpath, :url url, :xhr-url xhr-url,
          :title title, :form-valid? form-valid?, :fdefs fdefs]]
        ]])))


(def DS-NAME-FN #(str (:name %) " - " (:comment %)))


(defn cfg-object [class title fdefs template]
  {:list
   (render-list
     :vpath [:view :cfg class]
     :dpath [:data :cfg class]
     :data [(keyword "data" (str "cfg-" (name class) "-list"))]
     :on-refresh [:data/refresh :cfg class]
     :class (str "cfg-" (name class) "-list")
     :url (str "cfg/" (name class))
     :xhr-url (io/api "/cfg/" (name class))
     :title title
     :fdefs fdefs
     :template template)
   :edit
   (render-edit
     :title title
     :url (str "cfg/" (name class))
     :xhr-url (io/api "/cfg/" (name class))
     :on-refresh [(keyword "data" (str "cfg-" (name class) "-list"))]
     :vpath [:view :cfg class]
     :dpath [:data :cfg class]
     :fdefs fdefs)})


; Config: Applications

(def APP-FDEFS
  (zf/validated-fdefs
    [:view :cfg :app]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}
    {:attr :glyph, :label "Icon"}))

(def APP-OBJ-TEMPLATE
  {:id :new
   :name "newapp"
   :glyph "awe/cube"
   :comment "New Application"})

(let [cfo (cfg-object :app "Application" APP-FDEFS APP-OBJ-TEMPLATE)]
  (def app-list (:list cfo))
  (def app-edit (:edit cfo)))


; Config: Environments

(def ENV-FDEFS
  (zf/validated-fdefs
    [:view :cfg :env]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}
    {:attr :glyph, :label "Icon"}))


(def ENV-OBJ-TEMPLATE
  {:id :new
   :name "newenv"
   :comment "New Environment"})

(let [cfo (cfg-object :env "Environment" ENV-FDEFS ENV-OBJ-TEMPLATE)]
  (def env-list (:list cfo))
  (def env-edit (:edit cfo)))


; Config: Hosts

(def HOST-TEMPLATE
  {:id :new, :name "newhost", :comment "New Host"})

(def HOST-FDEFS
  (zf/validated-fdefs
    [:view :cfg :host]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}
    {:attr :authkey, :label "Auth key"}
    {:attr   :app, :label "Application", :show :detail, :ds-name-fn DS-NAME-FN,
     :widget :select, :ds-getter [:data/cfg-app-list], :type :int}
    {:attr   :env, :label "Environment", :show :detail, :ds-name-fn DS-NAME-FN,
     :widget :select, :ds-getter [:data/cfg-env-list], :type :int}))


(let [cfo (cfg-object :host "Host" HOST-FDEFS HOST-TEMPLATE)]
  (def host-list (:list cfo))
  (def host-edit (:edit cfo)))


; Config: Trace Types

(def TTYPE-TEMPLATE
  {:id :new, :name "newtype",
   :comment "New trace type", :glyph "awe/cube",
   :descr "FIXME: ${SOME_ATTR} -> ${OTHER_ATTR}"})

(def TTYPE-FDEFS
  (zf/validated-fdefs
    [:view :cfg :ttype]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}
    {:attr :glyph, :label "Icon"}
    {:attr :descr, :label "Desc template"}))


(let [cfo (cfg-object :ttype "Trace type" TTYPE-FDEFS TTYPE-TEMPLATE)]
  (def ttype-list (:list cfo))
  (def ttype-edit (:edit cfo)))

; Config: Host Registration Rules

(def HOSTREG-FDEFS
  (zf/validated-fdefs
    [:view :cfg :hostreg]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}
    {:attr :regkey, :label "Reg. key",}
    {:attr   :app, :label "Application", :show :detail, :ds-name-fn DS-NAME-FN
     :widget :select, :ds-getter [:data/cfg-app-list]}
    {:attr   :env, :label "Environment", :show :detail, :ds-name-fn DS-NAME-FN
     :widget :select, :ds-getter [:data/cfg-env-list]}))

(def HOSTREG-OBJ-TEMPLATE
  {:id :new, :name "newreg", :comment "New Registration"})


(let [cfo (cfg-object :hostreg "Registration" HOSTREG-FDEFS HOSTREG-OBJ-TEMPLATE)]
  (def hostreg-list (:list cfo))
  (def hostreg-edit (:edit cfo)))


; Admin: Users

(def USER-FDEFS
  (zf/validated-fdefs
    [:view :cfg :user]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}
    {:attr :fullname, :label "Full name", :list-col2 true}
    {:attr :email, :label "Email"}
    {:attr :flags, :label "Flags", :type :int}
    {:attr :password, :label "Password"}))

(def USER-OBJ-TEMPLATE
  {:id :new, :name "newuser"})

(let [cfo (cfg-object :user "User" USER-FDEFS USER-OBJ-TEMPLATE)]
  (def user-list (:list cfo))
  (def user-edit (:edit cfo)))


; Admin: Groups

(def GROUP-FDEFS
  (zf/validated-fdefs
    [:view :cfg :group]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}))

(def GROUP-OBJ-TEMPLATE
  {:name "NEW_GROUP" :comment "New Group"})

(let [cfo (cfg-object :group "Group" GROUP-FDEFS GROUP-OBJ-TEMPLATE)]
  (def group-list (:list cfo))
  (def group-edit (:edit cfo)))

