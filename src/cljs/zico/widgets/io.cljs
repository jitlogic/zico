(ns zico.widgets.io
  "All stateful event handlers and code is located here."
  (:require
    [re-frame.core :as rfc]
    [cljs.reader :refer [read-string]]
    [goog.events :as ge]
    [zico.widgets.util :as zutl]
    [clojure.string :as cs]
    [cemerick.url :as curl]
    [reagent.ratom :as ra])
  (:import
    goog.net.XhrIo
    goog.net.EventType
    [goog.events EventType]))

(def API-ROOT (atom "/api"))

(defn api [& path]
  (apply str @API-ROOT path))

(defn small-screen? []
  (< (.-innerWidth js/window) 512))


(defn alert-handler [db [_ msg]]
  (js/alert msg)
  db)


(defn println-handler [db [_ & args]]
  (println (str args))
  db)


(defn debug-handler [db v]
  (println "DEBUG: " (str v))
  db)

(defonce VIEW-ROOT (ra/atom "/view"))

(defn view-path [name params]
  (let [path @VIEW-ROOT]
    (if (and params (not (empty? params)))
      (str path "?" (zutl/url-encode params) "#" name)
      (str path "#" name))))

(def RE-VIEW-FILTER #".*/view/(.*)")

(defn current-page []
  (let [href (curl/url (-> js/window .-location .-href))
        [_ v0] (re-matches RE-VIEW-FILTER (:path href))]
    (assoc href
      :anchor (:anchor href (second v0))
      :query (into {} (for [[k v] (:query href)] {(keyword k) v}))
      :href href)))

(defonce CURRENT-PAGE (ra/atom (current-page)))

(ge/removeAll js/window goog.events.EventType.POPSTATE)

(ge/listen js/window goog.events.EventType.POPSTATE
           (fn [_] (reset! CURRENT-PAGE (current-page))))

(defn to-screen-handler [db [_ name params]]
  (let [path (view-path name params)]
    (swap! CURRENT-PAGE assoc :anchor name, :query params)
    (.pushState js/history nil nil path))
  (if (small-screen?) (assoc-in db [:view :menu :open?] false) db))

(defn history-push-handler [db [_ name params]]
  (.pushState js/history nil nil (view-path name params))
  db)

(defn history-replace-handler [db [_ name params]]
  (.replaceState js/history nil nil (view-path name params))
  db)

(defn history-back-handler [db _]
  (.back js/history)
  db)


(defn xhr-handler [db [_ method url & {:keys [data on-success on-error content-type]}]]
  "Event handler performs XHR request. Currently only EDN calls are supported.

  * method - one of :GET, :POST, :PUT, :DELETE

  * url - service URL

  * data - data (for POST/PUT requests etc.)

  * on-success - event to be dispatched on success (raw result to be appended using conj function)

  * on-error - event to be dispatched on error (raw text)"
  (let [xhr (XhrIo.)]
    (when on-success
      (ge/listen
        xhr goog.net.EventType.SUCCESS
        (fn [_] (rfc/dispatch (conj on-success (.getResponseText xhr))))))
    (when on-error
      (ge/listen
        xhr goog.net.EventType.ERROR
        (fn [_] (rfc/dispatch (conj on-error (.getResponseText xhr))))))
    (.send xhr url (cs/upper-case (zutl/to-string method)) data
           #js {"Content-Type" content-type, "Accept" content-type}))
  db)


(defn set-timeout-handler [db [_ timeout event]]
  (js/setTimeout (fn [] (rfc/dispatch event)) timeout)
  db)


(defn set-location-handler [db [_ url]]
  (set! (.-location js/window) url)
  db)

(defn write-to-clipboard-handler [db [_ text]]
  (.writeText (.-clipboard js/navigator) text)
  db)

(rfc/reg-event-db :alert alert-handler)
(rfc/reg-event-db :println println-handler)
(rfc/reg-event-db :debug debug-handler)
(rfc/reg-event-db :to-screen to-screen-handler)
(rfc/reg-event-db :xhr xhr-handler)
(rfc/reg-event-db :set-timeout set-timeout-handler)
(rfc/reg-event-db :set-location set-location-handler)
(rfc/reg-event-db :write-to-clipboard write-to-clipboard-handler)
(rfc/reg-event-db :history-push history-push-handler)
(rfc/reg-event-db :history-replace history-replace-handler)
