(defproject cljs-webrepl "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [cljsjs/clipboard "1.5.9-0"]
                 [cljsjs/material "1.2.1-0"]
                 [com.cognitect/transit-cljs "0.8.239"]
                 [com.taoensso/timbre "4.7.4"]
                 [environ "1.1.0"]
                 [figwheel "0.5.8"]
                 [hiccup "1.0.5"]
                 [lein-doo "0.1.7"]
                 [reagent "0.6.0"]
                 [reagent-utils "0.2.0"]
                 [replumb "0.2.4"]]

  :plugins [[lein-environ "1.0.2"]
            [lein-cljsbuild "1.1.1"]
            [lein-asset-minifier "0.2.7"]]

  :min-lein-version "2.5.0"

  :clean-targets ^{:protect false} [:target-path
                                    [:cljsbuild :builds :app :compiler :output-dir]
                                    [:cljsbuild :builds :app :compiler :output-to]]

  :resource-paths ["resources" "target/cljsbuild"]

  :minify-assets  {:assets
                   {"resources/public/css/site.min.css" "resources/public/css/site.css"}}

  :cljsbuild {:builds
              {:app {:source-paths ["src/cljs"]
                     :compiler     {:output-to     "target/cljsbuild/public/js/app.js"
                                    :output-dir    "target/cljsbuild/public/js/out"
                                    :asset-path    "js/out"
                                    :main          cljs-webrepl.prod
                                    :static-fns    true
                                    :optimizations :none
                                    :pretty-print  true}}}}

  :profiles {:dev
             {:plugins   [[lein-figwheel "0.5.8"]
                          [lein-doo "0.1.6"]
                          [com.cemerick/austin "0.1.6"]]

              :figwheel  {:http-server-root "public"
                          :server-port      3449
                          :nrepl-port       7001
                          :css-dirs         ["resources/public/css"]}

              :env       {:dev true}

              :cljsbuild {:builds {:app
                                   {:source-paths ["src/cljs" "env/dev/cljs"]
                                    :compiler     {:source-map true
                                                   :main       cljs-webrepl.dev}}
                                   :test
                                   {:source-paths ["src/cljs" "test/cljs" "env/dev/cljs"]
                                    :compiler     {:output-to     "target/test.js"
                                                   :main          cljs-webrepl.doo-runner
                                                   :optimizations :whitespace
                                                   :pretty-print  true}}}}}

             :prod    {:hooks       [minify-assets.plugin/hooks]
                       :prep-tasks  ["cljsbuild" "once"]
                       :env         {:production true}
                       :omit-source true
                       :cljsbuild
                       {:builds {:app
                                 {:source-paths ["src/cljs" "env/prod/cljs"]
                                  :compiler
                                  {:optimizations :none
                                   :pretty-print  false}}}}}

             :uberjar {:hooks       [minify-assets.plugin/hooks]
                       :prep-tasks  ["cljsbuild" "once"]
                       :env         {:production true}
                       :omit-source true
                       :cljsbuild
                       {:jar    true
                        :builds {:app
                                 {:source-paths ["src/cljs" "env/prod/cljs"]
                                  :compiler
                                  {:optimizations :none
                                   :pretty-print  false}}}}}})
