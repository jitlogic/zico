(ns zico.widgets.widgets
  (:require
    [reagent.core :as rc]
    [cljs-time.core :as ct]
    [cljs-time.format :as ctf]
    [zico.widgets.util :as zu]
    [zico.widgets.state :as zs]))


(defn svg-icon                                              ; TODO zamienić na macro
  ([family glyph color & {:keys [view-box class title] :or {view-box "0 0 1000 1000"}}]
   (let [uri (str "/img/" (zu/to-string family) ".svg#" (zu/to-string glyph))
         clazz (str "svg-icon c-" (zu/to-string color) class)]
     [:div (merge {:title title} {:class clazz})
      [:svg {:viewBox view-box}
       [:use {:xlink-href uri} " "]]])))


(defn svg-icon-2
  ([[f1 g1 c1] [f2 g2 c2] & {:keys [view-box class title style]
                   :or {view-box "0 0 1000 1000"}}]
   (let [u1 (str "/img/" (zu/to-string f1) ".svg#" (zu/to-string g1))
         u2 (str "/img/" (zu/to-string f2) ".svg#" (zu/to-string g2))
         cl1 (str "svg-icon-2 c-" (zu/to-string c1) class),
         cl2 (str "sub c-" (zu/to-string c2) class)]
     [:div (merge {:title title} {:class cl1})
      [:svg.icn {:viewBox view-box}
       [:use {:xlink-href u1} " "]]
      [:div {:class cl2}
       [:svg {:viewBox view-box}
        [:use {:xlink-href u2} " "]]]
      ])))


(defn svg-button [family glyph color title event & {:keys [opaque enabled?] :or {enabled? true}}]
  [(cond (zu/deref? opaque) :div (zu/deref? enabled?) :div.clickable :else :div.disabled)
   (merge {:title title} (when (and event (zu/deref? enabled?)) {:on-click (zs/to-handler event :sink true)}))
   (svg-icon family glyph color)])


(defn autofocus [widget]
  (rc/create-class
    {:reagent-render      (fn [] widget)
     :component-did-mount (fn [this] (.focus (rc/dom-node this)))}))


; Renders input box
(defn input [&{:keys [id getter setter type tag-ok tag-err valid? partial-valid? tooltip style attrs
                      on-update on-key-enter on-key-esc]
              :or {tag-ok :input.input, tag-err :input.input.error, type :string,
                   valid? true, partial-valid? (constantly true), attrs {}}}]
  "Renders input box: zico.schema.ui.widgets/InputWidget"
  (let [on-change #(let [text (.. % -target -value)]
                     (when (partial-valid? text)
                       (zs/dispatch-sync [:do (conj setter text) (conj on-update text)])))
        on-key-down (when (or on-key-enter on-key-esc)
                      #(case (.-keyCode %)
                         13 (when on-key-enter (zs/dispatch on-key-enter))
                         27 (when on-key-esc (zs/dispatch on-key-esc))
                         nil))
        attrs (merge {:type :text} attrs {:style style, :on-change on-change}
                     (when tooltip {:title tooltip})
                     (when id {:id id})
                     (when on-key-down {:on-key-down on-key-down}))]
    (fn []
      (let [text @getter]
        [(if (zu/deref? valid?) tag-ok tag-err)
         (assoc attrs :value text)]))))


; Renders option box
(defn select [&{:keys [id getter setter style type tag-ok tag-err ds-getter ds-val-fn ds-name-fn attrs valid?]
               :or {tag-ok :select.select, tag-err :select.error.select type :nil,
                    ds-getter identity, ds-val-fn first, ds-name-fn second, valid? true}}]
  "Renders select box: zico.schema.ui.widgets/SelectWidget"
  (let [rdata (zs/subscribe ds-getter)
        on-change #(let [text (.. % -target -value)]
                     (zs/dispatch-sync (conj setter text)))
        attrs (merge attrs {:style style, :on-change on-change} (when id {:id id}))]
    (fn []
      (let [text @getter, data @rdata]
        [(if (zu/deref? valid?) tag-ok tag-err)
         (assoc attrs :value text)
         [:option {:value nil} "-"]
         (for [d data :let [v (ds-val-fn d), n (ds-name-fn d)]]
           ^{:key v} [:option {:value v} n])]))))


(defn button [&{:keys [path icon text enabled? on-click]
               :or {enabled? true}}]
  (let [on-click (zs/to-handler (or on-click [:println "FIXME: button click handler:" path]) :sink true)
        on-click (fn [e] (when (zu/deref? enabled?) (on-click e)))]
    (fn []
      [(if (zu/deref? enabled?) :div.button :div.button.button-disabled)
       {:on-click on-click}
       (apply svg-icon icon) text])))


(def DATE-VALIDATION-RE #"^\d{0,4}-?\d{0,2}-?\d{0,2}")

(def DATE-INCOMPLETE-RE #"(^\d{0,4}$)|(^\d{4}-\d{0,2}$)|(^\d{4}-\d{2}-\d{0,2}$)")
(def DATE-FULL-RE #"(\d{4})-(\d{2})-(\d{2})")

(def MONTH-DAYS { 1 31, 2 29, 3 31, 4 30, 5 31, 6 30, 7 31, 8 31, 9 30, 10 31, 11 30, 12 31})

(def WEEK-DAYS ["Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"])

(def ^:private picker-formatter (ctf/formatter "yyyy-MM"))

(defn month-start
  ([] (month-start (ct/now)))
  ([t] (ct/date-time (ct/year t) (ct/month t) 1)))

(defn month-next
  ([] (month-next (month-start (ct/now))))
  ([t] (ct/plus t (ct/months 1))))

(defn month-prev
  ([] (month-prev (month-start (ct/now))))
  ([t] (ct/minus t (ct/months 1))))

(defn year-start
  ([] (year-start (ct/now)))
  ([t] (ct/date-time (ct/year t) 1 1)))

(defn year-next
  ([] (year-next (year-start (ct/now))))
  ([t] (ct/plus t (ct/years 1))))

(defn year-prev
  ([] (year-prev (year-start (ct/now))))
  ([t] (ct/minus t (ct/years 1))))

(defn day-start
  ([] (day-start (ct/now)))
  ([t] (ct/date-time (ct/year t) (ct/month t) (ct/day t))))

(defn day-next
  ([] (day-next (day-start (ct/now))))
  ([t] (ct/plus t (ct/days 1))))

(defn day-prev
  ([] (day-prev (day-start (ct/now))))
  ([t] (ct/minus t (ct/days 1))))



(defn calendar [{:keys [path] :as opts}]
  (let [state (zs/subscribe [:get path])
        mpath (conj path :mstart)]
    (fn []
      (let [{:keys [selected mstart]} @state
            mstart (or mstart (month-start)), ed (day-start (or selected (ct/now)))
            dstart (ct/minus mstart (ct/days (- (ct/day-of-week mstart) 1)))]
        [:div.calendar
         [:div.header
          [:div.nav
           {:on-click (zs/to-handler [:set mpath (year-prev mstart)] :sink true)}
           (svg-icon :awe :fast-bw :light)]
          [:div.nav
           {:on-click (zs/to-handler [:set mpath (month-prev mstart)] :sink true)}
           (svg-icon :awe :left-dir :light)]
          [:div.label (ctf/unparse picker-formatter mstart)]
          [:div.nav
           {:on-click (zs/to-handler [:set mpath (month-next mstart)] :sink true)}
           (svg-icon :awe :right-dir :light)]
          [:div.nav
           {:on-click (zs/to-handler [:set mpath (year-next mstart)] :sink true)}
           (svg-icon :awe :fast-fw :light)]]
         [:div.caption (for [d WEEK-DAYS] ^{:key d} [:div.cell (subs d 0 1)])]
         [:div.table
          (for [wn (range 6)]
            ^{:key wn}
            [:div.row
             (for [wd (range 7)
                   :let [d (ct/plus dstart (ct/days (+ wd (* 7 wn))))]
                   :let [ism (= (ct/month d) (ct/month mstart))]]
               ^{:key wd}
               [(if ism (if (ct/equal? d ed) :div.cell.sel :div.cell) :div.cell.nosel)
                {:on-click (zs/to-handler [(:on-click-kw opts :set) (conj path :selected) d])}
                (ct/day d)])])]
         ]))))
