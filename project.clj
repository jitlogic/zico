(defproject zico "2.0.0-SNAPSHOT"
  :description "Trace data collection and data presentation."
  :url "http://zorka.io"
  :license {:name "GPL v3"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.reader "1.3.2"]

                 [com.jitlogic.zorka/zorka-common "2.0.0-SNAPSHOT" :exclusions [com.jitlogic.zorka/zorka-slf4j]]
                 [prismatic/schema "1.1.9"]
                 [slingshot "0.12.2"]
                 [aero "1.1.3"]

                 [ring/ring-core "1.7.1"]
                 [ring/ring-devel "1.7.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-jetty-adapter "1.7.1"]
                 [bk/ring-gzip "0.3.0"]

                 [compojure "1.6.1"]
                 [metosin/compojure-api "2.0.0-alpha28"]
                 [hiccup "1.0.5"]

                 [io.micrometer/micrometer-core "1.3.5"]
                 [io.micrometer/micrometer-registry-prometheus "1.3.5"]
                 [io.micrometer/micrometer-registry-elastic "1.3.5"]

                 [clj-http "3.10.0"]
                 [org.slf4j/slf4j-api "1.7.25"]
                 [ch.qos.logback/logback-classic "1.0.13"]
                 [org.clojure/tools.logging "0.4.1"]]

  :plugins [[lein-environ "1.1.0"]
            [lein-cljsbuild "1.1.7"]
            [lein-asset-minifier "0.4.5" :exclusions [org.clojure/clojure]]]

  :ring {:uberwar-name "zico.war"}

  :min-lein-version "2.8.3"

  :uberjar-name "zico.jar"

  :test-paths [ "test/clj" ]

  :uberjar-exclusions ["public/css/zico.css"]

  :main zico.main

  :clean-targets ^{:protect false}
  [:target-path
   [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]]

  :source-paths ["src/clj" "src/cljc"]
  :resource-paths ["resources" "target/cljsbuild"]


  :minify-assets [[:css {:target "resources/public/css/zico.min.css" :source "resources/public/css/zico.css"}]]


  :cljsbuild
  {:builds {:min
            {:source-paths ["src/cljs" "src/cljc" "env/prod/cljs"]
             :compiler
             {:output-to "target/cljsbuild/public/js/app.js"
              :output-dir "target/uberjar"
              :optimizations :advanced
              :pretty-print  false}}
            :app
            {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
             :compiler
             {:main "zico.dev"
              :asset-path "/js/out"
              :output-to "target/cljsbuild/public/js/app.js"
              :output-dir "target/cljsbuild/public/js/out"
              :source-map true
              :optimizations :none
              :pretty-print  true}}
            :test
            {:source-paths ["src/cljs" "src/cljc" "env/test/cljs" "test/cljs"]
             :compiler
             {:main "zico.test-runner"
              :asset-path "target/cljstest/public/js/out"
              :output-to "target/cljstest/public/test.js"
              :output-dir "target/cljstest/public/js/out"
              :source-map true
              :optimizations :none
              :pretty-print  true}}
            }
   }

  :doo {:build "test", :alias {:default [:phantom]}}

  :figwheel
  {:http-server-root "public"
   :server-port      3449
   :nrepl-port       7002
   :nrepl-middleware ["cider.piggieback/wrap-cljs-repl"]
   :css-dirs         ["resources/public/css"]}


  :sass {:src "src/sass/" :dst "resources/public/css/"}

  :profiles {:dev      {:repl-options {:init-ns          zico.main
                                       :nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

                        :dependencies [[figwheel-sidecar "0.5.18"]
                                       [org.clojure/test.check "0.9.0"]
                                       [cider/piggieback "0.4.0"]
                                       [day8.re-frame/test "0.1.5"]]

                        :source-paths ["env/dev/clj"]
                        :plugins      [[lein-figwheel "0.5.18"]
                                       [lein-sassy "1.0.8"]
                                       [lein-doo "0.1.11"]]

                        :env          {:dev true}}

             :provided {
                        :dependencies [[org.clojure/clojurescript "1.10.238"]
                                       [com.cemerick/url "0.1.1"]
                                       [reagent "0.8.1"]
                                       [reagent-utils "0.3.1"]
                                       [re-frame "0.10.6"]
                                       [cljsjs/lz-string "1.4.4-1"]
                                       [com.andrewmcveigh/cljs-time "0.5.2"]
                                       [org.clojure/data.xml "0.0.8"]]}

             :uberjar  {:hooks        [minify-assets.plugin/hooks]
                        :source-paths ["env/prod/clj"]
                        :prep-tasks   ["compile" ["cljsbuild" "once" "min"]]
                        :env          {:production true}
                        :aot          :all
                        :omit-source  true}})
