# Zorka Intranet Collector

This is network collector for transaction traces generated by zorka agent. It's new version of collector,
handling new HTTP+CBOR protocol and it's still in early development phase, suitable as development snapshot,
not a ready to use product.  For production use, old [ZICO 1.x](https://github.com/jitlogic/zico-1.x) is recommended.


## Building instructions

Before building ZICO collector, make sure current version of [zorka-tdb](https://github.com/jitlogic/zorka-tdb) is
built and present in your `.m2` directory. JDK8 and [Leiningen](https://leiningen.org/) need to be present in `PATH`.  

In order to build production version run the following command in project directory::
 
```
lein do clean, sass once, uberjar
```

## Setting up development environment

In development mode, several components need to be started. In order to start ClojureScript compiler run the
following command in project directory:

```
lein figwheel
```

This will start a process that will watch for changes in source code and recompile changed files automatically. 
It uses [figwheel](https://github.com/bhauman/lein-figwheel) which is very cool plugin that automatically hot loads
changed code into your browser when developing.

CSS styles are compiled from `.scss` using SASS compiler. In order to start process waching and recompiling changes,
run command: 

```
lein sass watch
```

## Starting development instance

Development instance can be started either via `lein repl` or form IDE (I recommend 
[Cursive IDE](https://cursive-ide.com/) for developing Clojure projects).

Instance needs some working directory (it can be empty) that has to be passed via `zico.home` property. 
Also `zico.dev.mode` should be set in order to get hot code reload working. Example JVM parameters:

```
-Dzico.home=/tmp/zico-devel -Dzico.dev.mode=true -Xms512m -Xmx4096m
```

This will start instance with embedded H2 database. For configuring with MySQL, empty database has to be 
set up and `zico.conf` configured with MySQL has to be put into working directory. Schema and initial data
will be automatically loaded into database. 


# Documentation

For more information see [zorka.io](http://zorka.io) website. 

