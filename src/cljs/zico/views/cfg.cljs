(ns zico.views.cfg
  (:require
    [reagent.ratom :as ra]
    [zico.state :as zs]
    [zico.forms :as zf]
    [zico.widgets :as zw]
    [zico.views.common :as zv]))

(defn data-list-sfn [sectn view sort-attr]
  (fn [db [_]]
    (let [data (ra/reaction (get-in @db [:data sectn view]))
          srev (ra/reaction (get-in @db [:view sectn view :sort :rev]))]
      (ra/reaction
        (let [rfn (if @srev reverse identity)]
          (rfn (sort-by sort-attr (vals @data)))))
      )))

; Register all needed subscriptions
(doseq [k [:env :app :host :metric :poller :output :user :group :ttype :hostreg]]
  (zs/register-sub
    (keyword "data" (str "cfg-" (name k) "-list"))
    (data-list-sfn :cfg k :name)))


(defn cfg-object [class title fdefs template]
  {:list
   (zf/render-list
     :vpath [:view :cfg class]
     :dpath [:data :cfg class]
     :data [(keyword "data" (str "cfg-" (name class) "-list"))]
     :on-refresh [:data/refresh :cfg class]
     :class (str "cfg-" (name class) "-list")
     :url (str "cfg/" (name class))
     :xhr-url (str "/data/cfg/" (name class))
     :title title
     :fdefs fdefs
     :template template)
   :edit
   (zf/render-edit
     :title title
     :url (str "cfg/" (name class))
     :xhr-url (str "/data/cfg/" (name class))
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
  {:uuid :new
   :class :app
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
  {:uuid :new
   :class :env
   :name "newenv"
   :comment "New Environment"})

(let [cfo (cfg-object :env "Environment" ENV-FDEFS ENV-OBJ-TEMPLATE)]
  (def env-list (:list cfo))
  (def env-edit (:edit cfo)))


; Config: Hosts

(def HOST-TEMPLATE
  {:uuid :new, :class :host, :name "newhost", :comment "New Host"})

(def HOST-FDEFS
  (zf/validated-fdefs
    [:view :cfg :host]
    {:attr :name, :label "Name"}
    {:attr :comment, :label "Comment"}
    {:attr :authkey, :label "Auth key"}
    {:attr   :app, :label "Application", :show :detail,
     :widget :select, :rsub :data/cfg-app-list}
    {:attr   :env, :label "Environment", :show :detail,
     :widget :select, :rsub :data/cfg-env-list}))

(let [cfo (cfg-object :host "Host" HOST-FDEFS HOST-TEMPLATE)]
  (def host-list (:list cfo))
  (def host-edit (:edit cfo)))


; Config: Trace Types

(def TTYPE-TEMPLATE
  {:uuid :new, :class :ttype, :name "newtype",
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
    {:attr   :app, :label "Application", :show :detail,
     :widget :select, :rsub :data/cfg-app-list,}
    {:attr   :env, :label "Environment", :show :detail,
     :widget :select, :rsub :data/cfg-env-list}))

(def HOSTREG-OBJ-TEMPLATE
  {:uuid :new, :class :hostreg, :name "newreg", :comment "New Registration"})


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
    {:attr :password, :label "Password"}))

(def USER-OBJ-TEMPLATE
  {:uuid :new, :class :user, :name "newuser"})

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

