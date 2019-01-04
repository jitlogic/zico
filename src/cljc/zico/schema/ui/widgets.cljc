(ns zico.schema.ui.widgets
  (:require
    [schema.core :as s]))


(s/defschema EventHandler
  (s/pred #(or (fn? %) (vector? %))))


(s/defschema Function
  (s/pred fn?))


(s/defschema ValueGetter
  (s/pred #(or (fn? %) (string? %))))


(s/defschema EditWidget
  {:id      s/Str                                           ; DOM element ID
   :getter  ValueGetter                                     ; value getter (ref, subscription, reaction, string)
   :setter  [s/Any]                                         ; value setter (event)
   :type s/Keyword                                          ; data type
   :tag-ok  s/Keyword                                       ; HTML tag when in normal mode
   :tag-err s/Keyword                                       ; HTML tag when in error mode
   :valid? ValueGetter                                      ; if data is valid, returns true
   :partial-valid? ValueGetter                              ; if data valid but incomplete, returns true
   :tooltip s/Str                                           ; tooltip
   :style s/Str                                             ; custom CSS classes
   :attrs {s/Keyword s/Str}                                 ; custom CSS
   :on-update EventHandler                                  ; called when data is updated
   })


(s/defschema InputWidget
  (merge
    EditWidget
    {:on-key-enter EventHandler                             ; ENTER keystroke handler
     :on-key-esc EventHandler                               ; ESC keystroke handker
     }))


(s/defschema SelectWidget
  (merge
    EditWidget
    {:ds-getter ValueGetter                                ; data source subscription key
     :ds-val-fn Function                                   ; value function - maps subscribed data to value
     :ds-name-fn Function                                  ; name function - maps subscribed data to visible name
     }))

