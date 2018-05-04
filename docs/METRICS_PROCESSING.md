

### `metrics` table

This table contains definitions of all metric templates and instances. 

Table fields:

* `uuid` - record id

* `name` - unique name;

* `comment` - additional description;

* `template` - template from which metric inherits elements of its definition;
 
* `flags` - flags:

  * `TEMPLATE (0x01)` - indicates that this is a template rather than concrete instance;
  
  * `DISABLED (0x02)` - metric is disabled if set (pollers don't get called, data isn't processed);
  
  * `DRY_RUN (0x04)` - results won't be permanently stored; this is useful for testing metrics;

* `source` - for concrete instances, refers to agent description;

* `poller_type`, `poller` - defines and/or configures polling function for scrapped metrics or pre-processing function for 
trace pre-processing (before passing to filtering function);

* `filter_type`, `filter` - defines and/or configures filtering function for incoming data;

* `proc_type`, `processor` - defines and/or configures processing function for incoming (filtered) data;

