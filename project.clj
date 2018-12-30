(defproject zico "1.90.6-SNAPSHOT"
  :description "Trace data collection and data presentation."
  :url "http://zorka.io"
  :license {:name "GPL v3"}

  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.reader "1.3.2"]

                 [io.zorka/zorka-tdb "1.90.6-SNAPSHOT"]
                 [com.jitlogic.zorka/zorka-netkit "1.90.6-SNAPSHOT"]
                 [prismatic/schema "1.1.9"]
                 [slingshot "0.12.2"]

                 [ring/ring-core "1.7.1"]
                 [ring/ring-devel "1.7.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-jetty-adapter "1.7.1"]
                 [bk/ring-gzip "0.3.0"]

                 [compojure "1.6.1"]
                 [metosin/compojure-api "2.0.0-alpha28"]
                 ;[buddy/buddy-auth "2.1.0"]
                 [hiccup "1.0.5"]

                 [org.slf4j/slf4j-api "1.7.25"]
                 [com.taoensso/timbre "4.10.0"]

                 [honeysql "0.9.4"]
                 [org.clojure/java.jdbc "0.7.7"]
                 [org.apache.tomcat/dbcp "6.0.53"]
                 [com.h2database/h2 "1.4.197"]
                 [mysql/mysql-connector-java "5.1.46"]
                 [org.flywaydb/flyway-core "5.2.4"]]

  :plugins [[lein-environ "1.1.0"]
            [lein-cljsbuild "1.1.7"]
            [lein-asset-minifier "0.4.5" :exclusions [org.clojure/clojure]]]

  :ring {:handler zico.handler/app
         :uberwar-name "zico.war"}

  :min-lein-version "2.8.3"

  :uberjar-name "zico.jar"

  :test-paths [ "test/clj" ]

  :uberjar-exclusions [#"assets/.*.json" "public/css/zico.css"]

  :main zico.server

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

  :profiles {:dev      {:repl-options {:init-ns          zico.server
                                       :nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

                        :dependencies [[figwheel-sidecar "0.5.18"]
                                       [org.clojure/test.check "0.9.0"]
                                       [cider/piggieback "0.3.10"]
                                       [day8.re-frame/test "0.1.5"]]

                        :source-paths ["env/dev/clj"]
                        :plugins      [[lein-figwheel "0.5.18"]
                                       [lein-sassy "1.0.8"]
                                       [lein-doo "0.1.11"]]

                        :env          {:dev true}}

             :provided {
                        :dependencies [[org.clojure/clojurescript "1.10.238"]
                                       [reagent "0.8.1"]
                                       [reagent-utils "0.3.1"]
                                       [re-frame "0.10.6"]
                                       [secretary "1.2.3"]
                                       [cljsjs/lz-string "1.4.4-1"]
                                       [com.andrewmcveigh/cljs-time "0.5.2"]
                                       [org.clojure/data.xml "0.0.8"]
                                       [venantius/accountant "0.2.4" :exclusions [org.clojure/tools.reader]]]}

             :uberjar  {:hooks        [minify-assets.plugin/hooks]
                        :source-paths ["env/prod/clj"]
                        :prep-tasks   ["compile" ["cljsbuild" "once" "min"]]
                        :env          {:production true}
                        :aot          :all
                        :omit-source  true}})
