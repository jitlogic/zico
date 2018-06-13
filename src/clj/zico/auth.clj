(ns zico.auth
  (:require
    [org.httpkit.client :as http]
    [zico.util :as zutl]
    [taoensso.timbre :as log]
    [zico.objstore :as zobj]
    [ring.util.response :refer [redirect]])
  (:import (java.security MessageDigest)
           (javax.xml.bind DatatypeConverter)))


(def ANON-USER
  {:uuid :none
   :name "anonymous"
   :fullname "Anonymous Admin"
   :comment ""
   :email ""
   :flags 3})


(def ^:dynamic *current-user* ANON-USER)


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


(defn handle-cas10-login [{{{:keys [cas-url app-url]} :auth} :conf :keys [obj-store]}
                          {{:keys [ticket]} :params session :session :as req}]
  (if (nil? ticket)
    (redirect (str cas-url "/login?service=" app-url "/login"))
    (let [{:keys [body status]} @(http/request {:method :get, :url (str cas-url "/validate?service=" app-url "/login&ticket=" ticket)})
          [_ username] (when (string? body) (re-matches #"yes\n([A-Za-z0-9\.\-_]+)\n?.*" body))
          user (when username (zobj/find-and-get-1 obj-store {:class :user, :name username}))]
      (println "Username='" username "'")
      (cond
        (or (not= status 200) (nil? username))
        (render-message-form
          :error "CAS failed"
          "Contact system administrator."
          {:link "/" :msg "Or try again."})
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
      (str "SSHA512:" (DatatypeConverter/printBase64Binary hs)))))


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
  (let [user (zobj/find-and-get-1 obj-store {:name username})]
    (cond
      (nil? user) (login-failed username "User not found in database.")
      (password-check (:password user) password) (assoc (redirect "/") :session (assoc session :user (dissoc user :password)))
      :else (login-failed username "Password check failed."))))


(defn handle-login [{{:keys [auth]} :conf :as app-state}
                    {{:keys [user] :as session} :session :as req}]
  (cond
    (some? user) (redirect "/")
    (= :none (:auth auth)) (assoc (redirect "/") :session (assoc session :user ANON-USER))
    (= :cas10 (:auth auth)) (handle-cas10-login app-state req)
    (= :get (:request-method req)) (render-login-form)
    (= :local (:auth auth)) (handle-local-login app-state req)
    (= :ldap (:auth auth)) (handle-ldap-login app-state req)
    :else (render-login-form :error "Server misconfigured. Contact administrator.")))


(defn handle-logout [_ _]
  {:body (render-message-form :info "Logout" "Successfully logged out." {:link "/" :msg "Log in again"}),
   :status 200, :headers {"content-type" "text/html"}, :session {}})


(def AUTH-URI
  [["/view" :VIEWER]
   ["/user" :VIEWER]
   ["/data" :VIEWER]
   ["/info" :VIEWER]
   ["/admin" :VIEWER]
   ])


(defn auth-uri [uri]
  (or (first (for [[p m] AUTH-URI :when (.startsWith uri p)] m)) :NONE))


(defn wrap-user-auth [f]
  (fn [{:keys [uri session] :as req}]
    (let [auth (auth-uri uri)]
      (cond
        (= auth :NONE) (f req)
        (= auth :none) (f req)
        (nil? (:user session)) (redirect "/login")
        :else (binding [*current-user* (:user session)] (f req))))))


(defn wrap-keep-session [f]
  (fn [{:keys [session] :as req}]
    (let [resp (f req)]
      (assoc resp :session (:session resp session)))))


(defn change-password [{{:keys [auth]} :conf, :keys [obj-store]}
                       {{:keys [user]} :session, {:keys [oldPassword newPassword repeatPassword]} :data}]
  (cond
    (nil? user) (zutl/log-rest-error "Not logged in." 401)
    (not= :local (:auth auth)) (zutl/log-rest-error "Not available in this mode." 404)
    (not= newPassword repeatPassword) (zutl/log-rest-error "Password mismatch." 400 "user=" (:name user))
    (not (password-strong? newPassword)) (zutl/log-rest-error "Password too simple." 400 "user=" (:name user))
    :else
    (let [urec (zobj/find-and-get-1 obj-store {:uuid (:uuid user)})]
      (cond
        (nil? urec)
        (zutl/log-rest-error "User not in database." 403 "user=" (:name user))
        (not (password-check (:password urec) oldPassword))
        (zutl/log-rest-error "Authentication failed." 401 "user=" user)
        :else
        (do
          (nil? (zobj/put-obj obj-store (assoc urec :password (password-hash newPassword))))
          (zutl/rest-msg "Password changed."))
        ))))

