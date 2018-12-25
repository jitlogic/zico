(ns zico.schema.tdb
  (:require
    [schema.core :as s :include-macros true]))

(s/defschema QmiNode
  {:app s/Str
   :env s/Str
   :host s/Str
   :ttype s/Str
   (s/optional-key :min-duration) s/Int
   (s/optional-key :max-duration) s/Int
   (s/optional-key :min-calls) s/Int
   (s/optional-key :max-calls) s/Int
   (s/optional-key :min-errors) s/Int
   (s/optional-key :max-errors) s/Int
   (s/optional-key :min-recs) s/Int
   (s/optional-key :max-recs) s/Int
   (s/optional-key :tstart) s/Str
   (s/optional-key :tstop) s/Str
   (s/optional-key :dtrace-uuid) s/Str
   (s/optional-key :dtrace-tid) s/Str})

(s/defschema SearchNode
  (s/conditional
    #(#{:and :or} (:type %))
    {:type s/Keyword
     :args [#'SearchNode]}
    #(#{:text :xtext} (:type %))
    {:type s/Keyword
     :text s/Str
     (s/optional-key :match-start) s/Bool
     (s/optional-key :match-end) s/Bool}
    #(= :kv (:type %))
    {:type s/Keyword
     :key s/Str
     :val s/Str}))

(s/defschema TraceSearchQuery
  {(s/optional-key :node) SearchNode
   (s/optional-key :qmi) QmiNode
   (s/optional-key :limit) s/Int
   (s/optional-key :offset) s/Int
   (s/optional-key :after) s/Int
   (s/optional-key :sort-order) (s/pred #{:none :duration :calls :recs :errors})
   (s/optional-key :sort-reverse) s/Bool
   (s/optional-key :deep-search) s/Bool
   (s/optional-key :full-info) s/Bool})

(s/defschema TraceSearchRecord
  {(s/optional-key :uuid) s/Str
   (s/optional-key :chunk-id) s/Int
   (s/optional-key :descr) s/Str
   (s/optional-key :duration) s/Int
   (s/optional-key :ttype) s/Str
   (s/optional-key :app) s/Str
   (s/optional-key :env) s/Str
   (s/optional-key :host) s/Str
   (s/optional-key :tst) s/Int
   (s/optional-key :tstamp) s/Str
   (s/optional-key :data-offs) s/Int
   (s/optional-key :start-offs) s/Int
   (s/optional-key :flags) s/Int
   (s/optional-key :recs) s/Int
   (s/optional-key :calls) s/Int
   (s/optional-key :errs) s/Int
   (s/optional-key :details) s/Any
   })

(s/defschema TraceStackItem
  {:class s/Str
   :method s/Str
   :file s/Str
   :line s/Str})

(s/defschema TraceException
  {:class s/Str
   :msg s/Str
   :stack [TraceStackItem]
   :cause #'TraceException})

(s/defschema TraceRecord
  {:method s/Str
   :pos s/Str
   :errors s/Int
   :duration s/Int
   (s/optional-key :type) s/Str
   (s/optional-key :children) [#'TraceRecord]
   (s/optional-key :attrs) {s/Str s/Str}
   (s/optional-key :exception) TraceException})

(s/defschema TraceStatsRecord
  {:mid s/Int
   :recs s/Int
   :errors s/Int
   :sum-duration s/Int
   :max-duration s/Int
   :min-duration s/Int
   :method s/Str})

