(ns zico.views.cfg
  (:require
    [zico.state :as zs]
    [zico.popups :as zp]
    [zico.widgets :as zw]
    [zico.views.common :as zv]))


; Register all needed subscriptions
(doseq [k [:env :app :host :metric :poller :output :user :group :ttype :hostreg]]
  (zs/register-sub
    (keyword "data" (str "cfg-" (name k) "-list"))
    (zs/data-list-sfn :cfg k :name)))


(defn render-item [[sectn view] & {:keys [list-col1 list-col2] :or {list-col1 :name, list-col2 :comment}}]
  (fn [{:keys [uuid] :as obj}]
    ^{:key uuid}
    [:div.itm {:on-click (zs/to-handler [:toggle [:view sectn view :selected] uuid])}
     [:div.c1.dsc [:div.n (list-col1 obj)]]
     [:div.c2.cmt (list-col2 obj)]
     [:div.c3.icn
      (zw/svg-button
        :awe :toggle-on :blue "Disable"
        [:alert "TODO nieczynne - disable object" uuid])
      ]]))


(defn btn-delete-action [sectn view uuid name]
  [:popup/open :msgbox,
   :caption "Deleting object", :modal? true,
   :text (str "Delete object: " name " ?"),
   :buttons
   [{:id :ok, :text "Delete", :icon [:awe :ok :green]
     :on-click [:xhr/delete
                (str "../../../data/" (clojure.core/name sectn) "/" (clojure.core/name view) "/" uuid) nil nil
                :on-success [:dissoc [:data sectn view] uuid]]}
    {:id :cancel, :text "Cancel", :icon [:awe :cancel :red]}]])

(defn render-detail [[sectn view] fdefs]
  (fn [{:keys [uuid name comment] :as obj}]
    ^{:key uuid}
    [:div.det {:on-click (zs/to-handler [:toggle [:view sectn view :selected] uuid])}
     [:div.dsc
      [:div.kv
       [:div.c1.k "Name:"]
       [:div.c2.v name]
       [:div.c2.c.hide-s comment]]]
     [:div.btns
      [:div.ellipsis.c-gray uuid]
      (zw/svg-button :awe :clone :text (str "Clone " (clojure.core/name view))
                      [:form/edit-new sectn view obj fdefs])
      (zw/svg-button :awe :trash :red (str "Delete " (clojure.core/name view))
                     (btn-delete-action sectn view uuid name))
      (zw/svg-button :awe :edit :text (str "Edit " (clojure.core/name view))
                      [:form/edit-open sectn view uuid fdefs])
      [:div.s " "]
      (zw/svg-button
        :awe :toggle-on :blue "Disable"
        [:alert "TODO nieczynne disable" uuid])
      ]]))


(defn render-list [sectn view title & {:keys [fdefs template]}]
  (let [list-col1 (last (cons :name (for [fd fdefs :when (:list-col1 fd)] (:attr fd))))
        list-col2 (last (cons :comment (for [fd fdefs :when (:list-col2 fd)] (:attr fd))))
        env-item (render-item [sectn view] :list-col1 list-col1, :list-col2 list-col2)
        env-details (render-detail [sectn view] fdefs)]
    (fn [_]
      (zs/dispatch [:once :data/refresh sectn view])
      (zv/render-screen
        :toolbar [zv/list-screen-toolbar sectn view
                  {:title title,
                   :add-left
                          (when template
                            [:div (zw/svg-button
                                    :awe :plus :green (str "New " (name view))
                                    [:form/edit-new sectn view template fdefs])])}]
        :central [zv/list-interior [sectn view] env-item env-details]))))


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
              {:path [:view sectn view :edit :form attr], :type (or type :nil), :rsub rsub, :rvfn :uuid, :rnfn rnfn}]
             [zw/input
              {:path [:view sectn view :edit :form attr],
               :type (or type :nil)}])]])
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


; Config: Applications

(def APP-FDEFS
  [{:attr :name, :label "Name"}
   {:attr :comment, :label "Comment"}
   {:attr :glyph, :label "Icon"}
   ; TODO flags
   ])

(def APP-OBJ-TEMPLATE
  {:uuid :new
   :class :app
   :name "newapp"
   :glyph "awe/cube"
   :comment "New Application"})

(def app-list
  (render-list
    :cfg :app "Applications",
    :fdefs APP-FDEFS,
    :template APP-OBJ-TEMPLATE))

(def app-edit
  (render-edit
    :cfg :app "Application",
    :fdefs APP-FDEFS))


; Config: Environments

(def ENV-FDEFS
  [{:attr :name, :label "Name"}
   {:attr :comment, :label "Comment"}
   {:attr :glyph, :label "Icon"}
   ; TODO flags
   ])

(def ENV-OBJ-TEMPLATE
  {:uuid :new
   :class :env
   :name "newenv"
   :comment "New Environment"})

(def env-list
  (render-list
    :cfg :env "Environments"
    :fdefs ENV-FDEFS
    :template ENV-OBJ-TEMPLATE))

(def env-edit
  (render-edit
    :cfg :env "Environment"
    :fdefs ENV-FDEFS))


; Config: Hosts

(def HOST-TEMPLATE
  {:uuid :new, :class :host, :name "newhost", :comment "New Host"})

(def HOST-FDEFS
  [{:attr :name, :label "Name"}
   {:attr :comment, :label "Comment"}
   {:attr :authkey, :label "Auth key"}
   {:attr   :app, :label "Application", :show :detail,
    :widget :select, :rsub :data/cfg-app-list}
   {:attr   :env, :label "Environment", :show :detail,
    :widget :select, :rsub :data/cfg-env-list}
   ; TODO flags
   ])

(def host-list
  (render-list
    :cfg :host "Hosts",
    :fdefs HOST-FDEFS
    :template HOST-TEMPLATE))

(def host-edit
  (render-edit
    :cfg :host "Host",
    :fdefs HOST-FDEFS))


; Config: Trace Types

(def TTYPE-TEMPLATE
  {:uuid :new, :class :ttype, :name "newtype",
   :comment "New trace type", :glyph "awe/cube",
   :descr "FIXME: ${SOME_ATTR} -> ${OTHER_ATTR}"})

(def TTYPE-FDEFS
  [{:attr :name, :label "Name"}
   {:attr :comment, :label "Comment"}
   {:attr :glyph, :label "Icon"}
   {:attr :descr, :label "Desc template"}
   ; TODO flags
   ])

(def ttype-list
  (render-list
    :cfg :ttype "Trace types",
    :fdefs TTYPE-FDEFS,
    :template TTYPE-TEMPLATE))

(def ttype-edit
  (render-edit
    :cfg :ttype "Trace type",
    :fdefs TTYPE-FDEFS))


; Config: Host Registration Rules

(def HOSTREG-FDEFS
  [{:attr :name, :label "Name"}
   {:attr :comment, :label "Comment"}
   {:attr :regkey, :label "Reg. key"}
   {:attr   :app, :label "Application", :show :detail,
    :widget :select, :rsub :data/cfg-app-list}
   {:attr   :env, :label "Environment", :show :detail,
    :widget :select, :rsub :data/cfg-env-list}])

(def HOSTREG-OBJ-TEMPLATE
  {:uuid :new, :class :hostreg, :name "newreg", :comment "New Registration"})

(def hostreg-list
  (render-list
    :cfg :hostreg "Host registrations",
    :fdefs HOSTREG-FDEFS,
    :template HOSTREG-OBJ-TEMPLATE))

(def hostreg-edit
  (render-edit
    :cfg :hostreg "Host registration",
    :fdefs HOSTREG-FDEFS))


; Admin: Users

(def USER-FDEFS
  [{:attr :name, :label "Name"}
   {:attr :comment, :label "Comment"}
   {:attr :fullname, :label "Full name", :list-col2 true}
   {:attr :email, :label "Email"}
   {:attr :password, :label "Password"}])

(def USER-OBJ-TEMPLATE
  {:uuid :new, :class :user, :name "newuser"})

(def user-list
  (render-list
    :cfg :user "Users",
    :fdefs USER-FDEFS,
    :template USER-OBJ-TEMPLATE))

(def user-edit
  (render-edit
    :cfg :user "User",
    :fdefs USER-FDEFS))


; Admin: Groups

(def GROUP-FDEFS
  [{:attr :name, :label "Name"}
   {:attr :comment, :label "Comment"}])

(def group-list
  (render-list
    :cfg :group "Groups",
    :fdefs GROUP-FDEFS))

(def group-edit
  (render-edit
    :cfg :group "Group",
    :fdefs GROUP-FDEFS))

