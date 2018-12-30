(ns zico.auth
  (:require
    [zico.util :as zutl]
    [taoensso.timbre :as log]
    [zico.objstore :as zobj]
    [ring.util.response :refer [redirect]]
    [ring.util.http-response :as rhr]
    [clojure.data.xml :as xml]
    [clojure.string :as cs])
  (:import (java.security MessageDigest)
           (com.jitlogic.netkit.http HttpStreamClient HttpConfig HttpMessage)
           (com.jitlogic.zorka.common.util ZorkaUtil)))


(def ANON-USER
  {:id 0
   :name "anonymous"
   :fullname "Anonymous Admin"
   :comment ""
   :email ""
   :flags 3
   :roles #{:viewer :admin}})



(defn render-login-form [& {:keys [error info]}]
  (zutl/render-page
    [:form#login {:method :post :autocomplete :off, :action "/login"}
     [:div.login-form
      [:h1.login-title "ZICO"]
      (cond
        info [:div.msg.c-green info]
        error [:div.msg.c-red error]
        :else [:div.msg "&nbsp"])
      [:div.login-input
       [:div.lbl "Username:"]
       [:div.inp
        [:input#username {:name :username}]]]
      [:div.login-input
       [:div.lbl "Password:"]
       [:div.inp
        [:input#password {:name :password :type :password}]]]
      [:div.login-buttons
       [:input#btnSubmit {:type :submit :value "Login"}]]
      ]]))


(defn render-message-form [mode title & msgs]
  (zutl/render-page
    [:div.login-short.login-form.login-short
     [(case mode
        :error :h1.login-title.c-red
        :info :h1.login-title.c-green
        :h1.login-title) title]
     (for [msg msgs]
       (cond
         (string? msg) [:div.login-line msg]
         (and (map? msg) (:link msg)) [:div.login-line {:style "padding-top: 1.5em;"} [:a {:href (:link msg)} (:msg msg)]]
         :else [:div.login-line (str msg)]))]))


(defn login-failed
  ([username log-msg]
   (login-failed username log-msg "Login failed."))
  ([username log-msg user-msg]
   (log/info "Login failed for user " username " : " log-msg)
   (render-login-form :error user-msg)))


(defn http-get [{:keys [url]}]
  (with-open [conn (HttpStreamClient. (HttpConfig.) url)]
    (let [msg (.exec conn (HttpMessage/GET url nil))]
      ; TODO tls truststore etc.
      {:status (.getStatus msg)
       :body (.getBodyAsString msg)})))

(defn handle-cas10-login [{{{:keys [cas-url app-url cas-client]} :auth} :conf :keys [obj-store]}
                          {{:keys [ticket]} :params session :session :as req}]
  (if (nil? ticket)
    (redirect (str cas-url "/login?service=" app-url "/login"))
    (let [v-url (str cas-url "/validate?service=" app-url "/login&ticket=" ticket)
          {:keys [body status] :as res} (http-get (merge {:url v-url} cas-client))
          [_ username] (when (string? body) (re-matches #"yes\n([A-Za-z0-9\.\-_]+)\n?.*" body))
          user (when username (zobj/find-and-get-1 obj-store {:class :user, :name username}))]
      (cond
        (or (not= status 200) (nil? username))
        (do
          (log/error "CAS verification failed" res)
          (render-message-form
            :error "CAS failed"
            "Contact system administrator."
            {:link "/" :msg "Or try again."}))
        (nil? user)
        (render-message-form
          :error "Not allowed"
          "User not allowed to log in."
          "Contact system.administrator.")
        :else (assoc (redirect "/") :session (assoc session :user (dissoc user :password :class)))))))



(defn cas20-parse-response [r]
  (when r
    (let [[un {an :content}] (-> (xml/parse-str r) :content first :content)
          at (for [{t :tag c :content} an] [t (first c)])
          ag (for [[k [v & vs :as vv]] (group-by first at)]
               {k (if vs
                    (vec (map second vv))
                    (second v))})]
      {:id (-> un :content first)
       :attributes (into {} ag)})))


(defn attr-to-str [attrs expr]
  (cond
    (string? expr) (reduce #(.replace %1 (str (first %2)) (str (second %2))) expr (for [kv attrs] kv))
    (keyword? expr) (get attrs expr)
    :else (str expr)))


(defn attrs-to-user [{:keys [attrmap rolemap]} id attrs]  
  (let [roles (into #{} (get attrs (:attr rolemap)))
        attrs (into {} (for [[a x] attrmap] {a (attr-to-str attrs x)}))
        admin? (roles (:admin rolemap))]
    (if (or admin? (roles (:viewer rolemap)))
      (merge {:name id, :flags (if admin? 3 1)} attrs))))


(defn get-updated-user [{:keys [create? update?] :as account} obj-store id attributes]
  (when id
    (let [user (zobj/find-and-get-1 obj-store {:class :user, :name id})]
      (cond
        (and (nil? user) (not create?)) nil
        (and (some? user) (not update?)) user
        :else
        (when-let [uattrs (attrs-to-user account id attributes)]
          (zobj/put-obj obj-store (merge (or user {}) uattrs)))))))


(defn handle-cas20-login [{{{:keys [cas-url app-url cas-client]} :auth, account :account} :conf :keys [obj-store]}
                          {{:keys [ticket]} :params session :session :as req}]
  (if (nil? ticket)
    (redirect (str cas-url "/login?service=" app-url "/login"))
    (let [v-url (str cas-url "/serviceValidate?service=" app-url "/login&ticket=" ticket)
          {:keys [body status] :as res} (http-get (merge {:url v-url} cas-client))
          {:keys [id attributes]} (cas20-parse-response body)
          user (get-updated-user account obj-store id attributes)]
      (cond
        (or (not= status 200) (nil? id))
        (do
          (log/error "Error validating CAS2 ticket " res)
          (render-message-form
            :error "CAS failed"
            "Contact system administrator."
            {:link "/" :msg "Or try again."}))
        (nil? user)
        (render-message-form
          :error "Not allowed"
          "User not allowed to log in."
          "Contact system.administrator.")
        :else (assoc (redirect "/") :session (assoc session :user (dissoc user :password)))))))


(defn handle-ldap-login [_ _]
  (render-message-form :error "Not working." "LDAP login not implemented (yet)."))


(defn password-hash
  "Generates SSHA hash from password."
  ([password]
   (password-hash password (zutl/random-string 3 zutl/SALT-STR)))
  ([password salt]
    (let [md (MessageDigest/getInstance "SHA-512")
          hs (.digest md (.getBytes (str salt password)))]
      (str "SSHA512:" (ZorkaUtil/hex hs)))))


(defn password-check [pwhash password]
  "Checks SSHA512 password hash."
  (cond
    (.startsWith pwhash "SSHA512:")
    (first
      (for [c1 zutl/SALT-STR, c2 zutl/SALT-STR, c3 zutl/SALT-STR,
            :let [h (password-hash password (str c1 c2 c3))]
            :when (= pwhash h)] true))
    :else (= pwhash password)))


; Compared to UI part this is relaxed
(defn password-strong? [password]
  (and (string? password) (>= (count password) 8)))


(defn handle-local-login [{:keys [obj-store]} {{:keys [username password]} :params session :session}]
  (let [user (zobj/find-and-get-1 obj-store {:class :user :name username})
        roles (if (= (:flags user) 3) #{:viewer :admin} #{:viewer})
        usr1 (-> user (dissoc :password) (assoc :roles roles))]
    (cond
      (nil? user) (login-failed username "User not found in database.")
      (password-check (:password user) password) (assoc (redirect "/") :session (assoc session :user usr1))
      :else (login-failed username "Password check failed."))))


(defn handle-login [{{:keys [auth]} :conf :as app-state}
                    {{:keys [user] :as session} :session :as req}]
  (cond
    (some? user) (redirect "/")
    (= :none (:auth auth)) (assoc (redirect "/") :session (assoc session :user ANON-USER))
    (= :cas10 (:auth auth)) (handle-cas10-login app-state req)
    (= :cas20 (:auth auth)) (handle-cas20-login app-state req)
    (= :get (:request-method req)) (render-login-form)
    (= :local (:auth auth)) (handle-local-login app-state req)
    (= :ldap (:auth auth)) (handle-ldap-login app-state req)
    :else (render-login-form :error "Server misconfigured. Contact administrator.")))


(defn handle-logout [_ _]
  {:body (render-message-form :info "Logout" "Successfully logged out." {:link "/" :msg "Log in again"}),
   :status 200, :headers {"content-type" "text/html"}, :session {}})


(defn change-password [{{:keys [auth]} :conf, :keys [obj-store]} user {:keys [oldPassword newPassword repeatPassword]}]
  (cond
    (nil? user) (rhr/unauthorized {:reason "not logged in"})
    (not= :local (:auth auth)) (rhr/forbidden {:reason "not in this mode"})
    (not= newPassword repeatPassword) (rhr/unauthorized {:reason "password mismatch"})
    (not (password-strong? newPassword)) (rhr/unauthorized {:reason "password too simple"})
    :else
    (let [urec (zobj/find-and-get-1 obj-store {:id (:id user)})]
      (cond
        (nil? urec) (rhr/forbidden {:reason "user not in database"})
        (not (password-check (:password urec) oldPassword)) (rhr/forbidden {:reason "authentication failed"})
        :else
        (do
          (nil? (zobj/put-obj obj-store (assoc urec :password (password-hash newPassword))))
          (rhr/no-content))
        ))))


(defn wrap-zico-login-auth [f]
  (fn [{:keys [session] :as req}]
    (let [resp (if (:user session) (f req) (redirect "/login"))]
      (assoc resp :session (:session resp session)))))

; TODO rozszyć poszczególne metody logowania

(defn wrap-zico-auth [f auth]
  (cond
    (or (= auth :none) (nil? auth)) f
    :else (wrap-zico-login-auth f)))

