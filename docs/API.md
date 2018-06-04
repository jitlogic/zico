

# Collector API

This document regards API for configuration and GUI. Agent API is described in `zico-backend` documentation.





```
curl -H "Accept: application/edn" http://localhost:8640/conf/objects/metric
```


* `/conf/objects/:class` - configuration objects of the following classes presented via conventional REST interface;

  * `host` - monitored hosts (ie. `/conf/objects/host`);
  
  * `poller` - definitions of pollers;
  
  * `metric` - metric calculation rules;

  * `output` - configured outputs (submitting metric data to external systems);
  
  * `user` - system users;
  
  * `group` - groups of users;
  
  * `query` - custom queries for configuration objects of metrics;


* `/query/:name` - various custom queries (configured as `query` objects);



## Configuration objects

* `/conf/objects/:class` - 


## Query interface

```
curl -H 'Accept: application/edn' -H 'Content-Type: application/edn' -X POST -d '{:limit 10, :offset 10}' http://localhost:8640/data/trace/search
```


## Trace data


# Agent interface




