(ns zico.schema.tdb
  (:require
    [schema.core :as s :include-macros true]))


(s/defschema TraceException
  {:class                  s/Str
   :msg                    (s/maybe s/Str)
   :stack                  [s/Str]
   (s/optional-key :cause) (s/recursive #'TraceException)})


(s/defschema TraceRecord
  {:method                     s/Str
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
  {(s/optional-key :traceid)      s/Str                     ; Look for exact trace ID
   (s/optional-key :spanid)       s/Str                     ; Look for exact span ID
   (s/optional-key :limit)        s/Int
   (s/optional-key :offset)       s/Int
   (s/optional-key :order-by)     (s/enum :tst :duration :calls :recs :errors)
   (s/optional-key :order-dir)    (s/enum :asc :desc)
   (s/optional-key :fetch-attrs)  s/Bool
   (s/optional-key :errors-only)  s/Bool
   (s/optional-key :spans-only)   s/Bool
   (s/optional-key :min-tstamp)   s/Str                     ; ?? Date
   (s/optional-key :max-tstamp)   s/Str                     ; ?? Date
   (s/optional-key :min-duration) s/Int
   (s/optional-key :attr-matches) {s/Str s/Str}
   (s/optional-key :text)         s/Str
   (s/optional-key :match-start)  s/Bool
   (s/optional-key :match-end)    s/Bool
   })


(s/defschema ChunkMetadata
  {:traceid                   s/Str
   :spanid                    s/Str
   (s/optional-key :parentid) (s/maybe s/Str)
   (s/optional-key :chnum)    s/Int
   (s/optional-key :tsnum)    s/Int
   (s/optional-key :tst)      s/Int                       ; Timestamp in milliseconds since Epoch
   (s/optional-key :top-level) s/Bool
   (s/optional-key :tstamp)   s/Str                       ; Timestamp
   (s/optional-key :desc)     s/Str
   (s/optional-key :error)    s/Bool
   (s/optional-key :duration) s/Int
   (s/optional-key :klass)    s/Str
   (s/optional-key :method)   s/Str
   (s/optional-key :ttype)    s/Str
   (s/optional-key :recs)     s/Int
   (s/optional-key :calls)    s/Int
   (s/optional-key :errors)   s/Int
   (s/optional-key :tdata)    (s/maybe s/Str)
   (s/optional-key :attrs)    {s/Str s/Any}
   (s/optional-key :children) [(s/recursive #'ChunkMetadata)]
   })

