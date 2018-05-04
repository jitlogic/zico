(ns zico.popups
  (:require
    [zico.state :as zs]
    [zico.widgets :as zw]
    [zico.util :as zu]))


(def POPUP-ROOT [:view :popups])

(def close-popup (zs/to-handler [:popup/close]))

(zs/reg-event-db
  :popup/open
  (fn [db [_ type & {:as args}]]
    (let [stack (get-in db POPUP-ROOT)]
      (assoc-in db POPUP-ROOT (cons [type args] stack)))))


(zs/reg-event-db
  :popup/close
  (fn [db [_]]
    (let [stack (get-in db POPUP-ROOT)]
      (assoc-in db POPUP-ROOT (rest stack)))))


(defmulti render-popup (fn [type _] type))

; TODO przycisk 'close' w prawym górnym rogu okienka wyrenderować (opcjonalnie)
(defn render-popup-stack []
  (let [state (zs/subscribe [:get POPUP-ROOT])]
    (fn []
      (when-let [[type {:keys [position modal? caption] :as args}] (first @state)]
        [:div.popup-panel
         [:div.background (when-not modal? {:on-click close-popup})]
         [:div.frame
          (merge
            {:class (or position :mid-mid)}
            (when-not modal? {:on-click close-popup}))
          (when caption
            [:div.caption
             [:div.flexible.centered caption]])
          [render-popup type args]]
         ]))))

; TODO obsłużyć jeszcze ikonkę
(defmethod render-popup :msgbox [_ {:keys [text icon buttons on-action]}]
  "Renders message box"
  (fn []
    [:div.interior.popup-msgbox
     [:div.msg.centered
      (cond
        (vector? text) (for [[t n] (map vector text (range))] ^{:key n} [:div t])
        (string? text) [:div text]
        :else [:div (str text)])]
     (when buttons
       [:div.button-row
        (for [{:keys [id icon text on-click]} buttons]
          ^{:key id}
          [zw/button
           {:icon     icon, :text text,
            :on-click [:do on-click (or on-action [:popup/close])]}])])]))


(def MENU-ITEM-STATES
  {:normal   :div.item
   :checked  :div.item.item-checked
   :selected :div.item.item-selected
   :disabled :div.item.item-disabled})


(defmethod render-popup :menu [_ {:keys [items]}]
  (fn []
    [:div.interior.popup-menu
     ;(println (zu/deref? items))
     (for [{:keys [key separator? text icon on-click state]} (zu/deref? items) :let [[family glyph glyph-color] icon]]
       (if separator?
         ^{:key key} [:div.separator ""]
         ^{:key key} [(MENU-ITEM-STATES state :div.item)
                      {:on-click (zs/to-handler on-click)}
                      [:div.icon
                       (when glyph (zw/svg-icon (or family :awe) glyph (or glyph-color :light)))] text]))]))


(defmethod render-popup :calendar [_ args]
  (fn [] [:div.interior [zw/calendar args]]))

