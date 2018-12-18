(defproject zico "1.90.6-SNAPSHOT"
  :description "Trace data collection and data presentation."
  :url "http://zorka.io"
  :license {:name "GPL v3"}

  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/core.async "0.3.465"]

                 [io.zorka/zorka-tdb "1.90.6-SNAPSHOT"]
                 [com.jitlogic.zorka/zorka-netkit "1.90.6-SNAPSHOT"]
                 [http-kit "2.3.0"]

                 [slingshot "0.12.2"]

                 [ring/ring-core "1.6.3"]
                 [ring/ring-devel "1.6.3"]
                 [ring/ring-defaults "0.3.1"]

                 [compojure "1.6.0"]
                 [hiccup "1.0.5"]

                 [org.slf4j/slf4j-api "1.7.25"]
                 [com.taoensso/timbre "4.10.0"]

                 [honeysql "0.9.1"]
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.apache.tomcat/dbcp "6.0.45"]
                 [com.h2database/h2 "1.4.191"]
                 [mysql/mysql-connector-java "5.1.46"]
                 [org.flywaydb/flyway-core "5.0.2"]]

  :plugins [[lein-environ "1.1.0"]
            [lein-cljsbuild "1.1.7"]
            [lein-asset-minifier "0.2.7" :exclusions [org.clojure/clojure]]]

  :ring {:handler zico.handler/app
         :uberwar-name "zico.war"}

  :min-lein-version "2.5.0"

  :uberjar-name "zico.jar"

  :test-paths [ "test/clj" ]

  :uberjar-exclusions [#"assets/.*.json" "public/css/zico.css"]

  :main zico.server

  :clean-targets ^{:protect false}
  [:target-path
   [:cljsbuild :builds :app :compiler :output-dir]
   [:cljsbuild :builds :app :compiler :output-to]]

  :source-paths ["src/clj"]
  :resource-paths ["resources" "target/cljsbuild"]


  :minify-assets {:assets {"resources/public/css/zico.min.css" "resources/public/css/zico.css"}}


  :cljsbuild
  {:builds {:min
            {:source-paths ["src/cljs" "env/prod/cljs"]
             :compiler
             {:output-to "target/cljsbuild/public/js/app.js"
              :output-dir "target/uberjar"
              :optimizations :advanced
              :pretty-print  false}}
            :app
            {:source-paths ["src/cljs" "env/dev/cljs"]
             :compiler
             {:main "zico.dev"
              :asset-path "/js/out"
              :output-to "target/cljsbuild/public/js/app.js"
              :output-dir "target/cljsbuild/public/js/out"
              :source-map true
              :optimizations :none
              :pretty-print  true}}
            :test
            {:source-paths ["src/cljs" "env/test/cljs" "test/cljs"]
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
   :nrepl-middleware ["cemerick.piggieback/wrap-cljs-repl"]
   :css-dirs         ["resources/public/css"]}


  :sass {:src "src/sass/" :dst "resources/public/css/"}

  :profiles {:dev      {:repl-options {:init-ns          zico.server
                                       :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

                        :dependencies [[figwheel-sidecar "0.5.14"]
                                       [org.clojure/test.check "0.9.0"]
                                       [com.cemerick/piggieback "0.2.2"]
                                       [day8.re-frame/test "0.1.5"]]

                        :source-paths ["env/dev/clj"]
                        :plugins      [[lein-figwheel "0.5.14"]
                                       [lein-sassy "1.0.8"]
                                       [lein-doo "0.1.10"]]

                        :env          {:dev true}}

             :provided {
                        :dependencies [[org.clojure/clojurescript "1.9.946"]
                                       [reagent "0.7.0"]
                                       [reagent-utils "0.2.1"]
                                       [re-frame "0.10.2"]
                                       [secretary "1.2.3"]
                                       [com.andrewmcveigh/cljs-time "0.5.2"]
                                       [org.clojure/data.xml "0.0.8"]
                                       [venantius/accountant "0.2.3" :exclusions [org.clojure/tools.reader]]]}

             :uberjar  {:hooks        [minify-assets.plugin/hooks]
                        :source-paths ["env/prod/clj"]
                        :prep-tasks   ["compile" ["cljsbuild" "once" "min"]]
                        :env          {:production true}
                        :aot          :all
                        :omit-source  true}})
