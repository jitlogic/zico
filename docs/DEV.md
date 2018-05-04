
# Error handling, exceptions

Errors and exceptions are represented by maps and thrown using slingshot's `throw+` clause:

```
   (throw+ {:level :error, 
            :source "zico-collector.trace/submit-agent-state", 
            :action :SUBMIT 
            :msg "Session not found" 
            :req/session-uuid "..."})
```

Common attributes:

* `:level` - `:error` for correctable errors, `:fatal` for uncorrectable errors;

* `:source` - either `namespace/function` or other string describing event source;

* `:action` - actual performed when error occured, eg. `:SUBMIT`, `:AUTH`, `:QUERY` etc.

* `:msg` - error message;

* `:namespace/keyword` - custom attributes are namespaced;

