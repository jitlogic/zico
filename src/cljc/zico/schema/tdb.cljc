(ns zico.schema.tdb
  (:require
    [schema.core :as s :include-macros true]))


(s/defschema TraceStackItem
  {:class s/Str
   :method s/Str
   :file s/Str
   :line s/Int})


(s/defschema TraceException
  {:class                  s/Str
   :msg                    (s/maybe s/Str)
   :stack                  [TraceStackItem]
   (s/optional-key :cause) (s/recursive #'TraceException)})


(s/defschema TraceMethod
  {:result s/Str
   :package s/Str
   :class s/Str
   :method s/Str
   :args s/Str})


(s/defschema TraceRecord
  {:method                     TraceMethod
   :pos                        s/Int
   :errors                     s/Int
   :duration                   s/Int
   :tstart                     s/Int
   (s/optional-key :trace-id)  s/Str
   (s/optional-key :span-id)   s/Str
   (s/optional-key :children)  [(s/recursive #'TraceRecord)]
   (s/optional-key :attrs)     {s/Str s/Str}
   (s/optional-key :exception) TraceException})


(s/defschema TraceStats
  {:mid s/Int
   :recs s/Int
   :errors s/Int
   :sum-duration s/Int
   :max-duration s/Int
   :min-duration s/Int
   :method s/Str})


(s/defschema TraceSearchQuery
  {(s/optional-key :limit)        s/Int
   (s/optional-key :offset)       s/Int
   (s/optional-key :fetch-attrs)  s/Bool
   (s/optional-key :errors-only)  s/Bool
   (s/optional-key :spans-only)   s/Bool
   (s/optional-key :min-tstamp)   s/Str                     ; ?? Date
   (s/optional-key :max-tstamp)   s/Str                     ; ?? Date
   (s/optional-key :min-duration) s/Int
   (s/optional-key :attr-matches) {s/Str s/Str}
   })


(s/defschema ChunkMetadata
  {:trace-id                    s/Str
   :span-id                     s/Str
   :chunk-num                   s/Int
   (s/optional-key :parent-id)  (s/maybe s/Str)
   (s/optional-key :error)      s/Bool
   (s/optional-key :tst)        s/Int                       ; Timestamp in milliseconds since Epoch
   (s/optional-key :tstamp)     s/Str                       ; Timestamp
   (s/optional-key :duration)   s/Int
   (s/optional-key :recs)       s/Int
   (s/optional-key :calls)      s/Int
   (s/optional-key :errs)       s/Int
   (s/optional-key :attrs)      {s/Str s/Any}
   (s/optional-key :children)   [(s/recursive #'ChunkMetadata)]
   })

