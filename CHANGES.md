ZICO 1.90.9 (2020-??-??)
------------------------

* flattened-attrs mode for Elastic with X-Pack;
* liveness endpoint: /healthz;
* elastic search connection pooling & keep alive;

ZICO 1.90.8 (2020-02-20)
------------------------

* TLS + http authentication between zico and elastic;
* password encryption for local users;
* expose collector metrics to prometheus & elastic;
* performance: cache symbols and method defs;
* admin API (index rotation, list symbols);
* fixes: session timeout, elastic field mappings overflow;


ZICO 1.90.7 (2020-02-11)
------------------------

* get rid of internal on-disk trace store, collector is now stateless;
* use Elastic as backend for trace storage;
* memory trace store - for quick development setups;
* simplify collector, get rid of internal SQL config database;
* use OpenTracing compliant labels and attribute names;


ZICO 1.90.6 (2019-01-19)
------------------------

* UI fixes and cleanups;
* internal refactorings;
* switched to binary agent-collector protcol (no base64 anymore);
* switch to jetty, optional TLS/HTTPS;


ZICO 1.90.5 (2018-11-05)
------------------------

* CAS 2.0 integration and automatic account creation (based on user attributes);
* copy-to-clipboard for trace/method attributes and exceptions;
* move data initialization from flyway SQL scripts to EDN files (zico.jar or external);
* lots ot small UI fixes;
* HTTPS mode (TLS only);


ZICO 1.90.4 (2018-09-24)
------------------------

* collapsible trace tree view;
* internal refactorings
* UI code cleanups and refactorings;
* config views: proper form validation;
* refactor: split form code from widgets;
* server error handling and display;
* trace method call stats view;


ZICO 1.90.3 (2018-08-16)
------------------------

* distributed trace tree view;
* usable defaults (`java -jar zico.jar` now works);


ZICO 1.90.2 (2018-06-14)
-------------------------

* uses new trace search API implemented in zorka-tdb; 
* reworked trace filtering functionalities;
* more config edit views (yet still not fully complete);
* agent/ui now on separate ring handler chains & other fixes;


ZICO 1.90.1 (2018-05-04)
-------------------------

* first publicly available version (alpha1);
* written in Clojure & ClojureScript;
* new storage engine with full text search capability (see zorka-tdb);
* not (yet) suitable for anything other than testing collector itself;

