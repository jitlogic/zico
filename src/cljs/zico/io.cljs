(ns zico.io
  "All stateful event handlers and code is located here."
  (:require
    [re-frame.core :as rfc]
    [secretary.core :as sc]
    [cljs.reader :refer [read-string]]
    [goog.events :as ge]
    [zico.util :as zutl]
    [clojure.string :as cs])
  (:import
    goog.net.XhrIo
    goog.net.EventType
    [goog.events EventType]))


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


(defn to-screen-handler [db [_ name params]]
  (sc/dispatch! (str "/view/" name))
  (if (small-screen?) (assoc-in db [:view :menu :open?] false) db))


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

