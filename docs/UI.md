
# URI structure

UI structure mirrors structure of data URIs exposed by server:
* `/view/<section>/<component>` - views (returns HTML stubs);
* `/data/<section>/<component>` - data (REST interface);

The following sections and components are now defined:

* `mon` - monitoring - viewing current state;

  * `mon/app` - application monitor view;

  * `mon/host` - host monitor view;

* `cfg` - configuration data viewing;

  * `cfg/app` - 
  
  * `cfg/env` - 

* `edt` - editing forms (for example configuration objects); 


# Client application state

* `[:view]` - internal state of UI components;

* `[:data]` - data fetched from server;

* `[:user]` - currently logged user data;


## View

View data structure mirrors URI structure, for example: `[:view :cfg :host]`

Each subtree (eg. `[:view :mon :host]`) contains the following items:
 
* `:selected` - UUID of selected item;

* `:sort-order` - sort order (`:up` or `:down`);

* `:search` - search box data

  * `:open?` - true if search box is currently visible;
  
  * `:text` - search box text;
  
  * `:results` - search box results as set of UUIDS;

* `:filters :*` - set of filters defined for current view; each filter has following attributes:

  * `:open?` - true if filter menu is currently open;
  
  * `:selected` - selected item (UUID for apps/envs, ) 


### Main menu



* `[:view :menu]` - main menu (left slideout);

  * `:open?` - 
  

## Data

Data structure mirrors URI structure, for example `[:data :cfg :hosts]`, `[:data :cfg :apps]`.

The following components are there:

* `[:data :cfg :*]` - configuration objects mapped by `:uuid` field;

* `[:data :mon :*]` - monitoring data mapped by `:uuid` field;


## User data

* `:user` - current user information

  * `:login` - login name
  
  * `:roles` - user roles
  
  * `:name` - full name; 

