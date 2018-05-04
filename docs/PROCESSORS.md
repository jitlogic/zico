# Overview

Processing functions generate metric data in unified forms:


## Utility functions

Two functions that 

```
(delta f & args)
(rate f & args)
```

* `:*`, `:/`, `:+`, `:-` - multiply, divide, add, subtract constant to a result; 



## Processing functions

### trace-zstats

This function transforms zorka traces into data samples. For each trace one or two samples are created: one representing 
number of calls (always 1), second (optional) representing errors (also 1). 

```
(trace-zstats prefix attr-map & {:keys [is-error?]})
```

Prefix is mandatory and will be used in all time series produced by this processor.

Mandatory arguments:

```
attr-map 
```

Declares additional attributes to be added to result data. Attributes are declared as map where keys contain
target attributes and values contain source paths and processing rules. 

Attribute paths can be declared as keywords or vectors that for path into trace data structure, for example:

* `"SomeString` - constant value (must be string or number);

* `:URI` - extracts top level attribute `URI`;

* `:headers.in.User-Agent` - extracts `User-Agent` input header - descends to `headers` in top level, then `in` and
then extracts `User-Agent` value;

* `[:headers.in "User-Agent"]` - alternative way suitable when attribute names contain characters that cannot be used
in LISP keywords;

* `#(-> % :URI .trim .toLowerCase (re-match #"/data/(.*)" 1) #{"users" "jobs" "docs" "submit"})` sample processing rule;


Examples:

```
(trace-zstats :http.data 
  { :uri #(some->> % 
           :URI 
           .trim 
           .toLowerCase 
           (re-matches #"/data/(.*)") 
           second 
           (filter #{"users" "jobs" "docs" "submit"})
        )})
```






### trace-jmx


Examples:

JVM memory pools - simple statistics, one custom function. 

```
(trace-jmx  
  { :pool :name }
  :jvm_mp.used  :Usage.cur 
  :jvm_mp.max   :Usage.max 
  :jvm_mp.util  #(* 100.0 (div (-> % :Usage :cur) (-> % :PeakUsage :max)))
```

JVM garbage collectors - calculates CPU utilization based on CPU time:

```
(trace-jmx 
  { :name :name }
  :gc.util1 (rate #(* 10 (-> % :CollectionTime)))
  :gc.util2 (rate :CollectionTime :* 10)              ; equivalent of 
  :gc.count (rate :CollectionCount)
  )
```

Zorka stats for everything:

```
(trace-jmx { :name :name, :tag :tag } :stats :stats.<*:tag>)
```

Collector will automatically create proper sample records if it detects zorka stats data. 
