(defproject cljs-webrepl "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]
                 [com.cognitect/transit-cljs "0.8.239"]
                 [com.taoensso/timbre "4.7.4"]
                 [environ "1.1.0"]
                 [figwheel "0.5.8"]
                 [fipp "0.6.6"]
                 [hiccup "1.0.5"]
                 [lein-doo "0.1.7"]
                 [reagent "0.6.0"]
                 [reagent-utils "0.2.0"]
                 [replumb "0.2.4"]]

  :plugins [[lein-environ "1.0.2"]
            [lein-cljsbuild "1.1.4"]
            [lein-asset-minifier "0.2.7"]]

  :min-lein-version "2.5.0"

  :clean-targets ^{:protect false} [:target-path
                                    [:cljsbuild :builds :app :compiler :output-dir]
                                    [:cljsbuild :builds :app :compiler :output-to]]

  :resource-paths ["resources" "target/cljsbuild"]

  :minify-assets  {:assets
                   {"resources/public/css/site.min.css" "resources/public/css/site.css"}}

  :cljsbuild {:builds {:frontend
                       {:source-paths ["src-frontend/cljs" "src-shared/cljs"]
                        :compiler     {:output-to      "target/cljsbuild/public/js/app.js"
                                       :output-dir     "target/cljsbuild/public/js/app"
                                       :asset-path     "js/app"
                                       :main           cljs-webrepl.prod
                                       :static-fns     true
                                       :optimizations  :none
                                       :pretty-print   true
                                       :parallel-build true}}

                       :backend
                       {:source-paths ["src-backend/cljs" "src-shared/cljs"]
                        :compiler     {:output-to      "target/cljsbuild/public/js/repl-thread.js"
                                       :output-dir     "target/cljsbuild/public/js/repl-thread"
                                       :asset-path     "js/repl-thread"
                                       :main           cljs-webrepl.repl-thread-prod
                                       :static-fns     true
                                       :optimizations  :whitespace
                                       :pretty-print   true
                                       :parallel-build true}}}}

  :profiles {:frontend-dev  {:dependencies [[cljsjs/clipboard "1.5.9-0"]
                                            [cljsjs/material "1.2.1-0"]
                                            [cljsjs/codemirror "5.19.0-0"]]

                             :plugins      [[lein-figwheel "0.5.8"]]

                             :figwheel     {:http-server-root "public"
                                            :server-port      3449
                                            :nrepl-port       7001
                                            :css-dirs         ["resources/public/css"]
                                            :load-all-builds  true}

                             :env          {:dev true}

                             :cljsbuild    {:builds {:frontend
                                                     {:source-paths ["src-frontend/cljs-dev"]
                                                      :compiler     {:source-map true
                                                                     :main       cljs-webrepl.dev}}}}}
             :backend-dev   {:env       {:dev true}
                             :cljsbuild {:builds {:backend
                                                  {:source-paths ["src-backend/cljs-dev"]
                                                   :compiler     {:source-map "target/cljsbuild/public/js/repl-thread.js.map"
                                                                  :main       cljs-webrepl.repl-thread-dev}}}}}

             :frontend-prod {:dependencies [[cljsjs/clipboard "1.5.9-0"]
                                            [cljsjs/material "1.2.1-0"]
                                            [cljsjs/codemirror "5.19.0-0"]]
                             :hooks        [minify-assets.plugin/hooks]
                             :env          {:production true}
                             :omit-source  true
                             :cljsbuild    {:builds {:frontend
                                                     {:source-paths ["src-frontend/cljs-prod"]
                                                      :compiler     {:optimizations :advanced
                                                                     :pretty-print  false}}}}}

             :backend-prod  {:env         {:production true}
                             :omit-source true
                             :cljsbuild   {:builds {:backend
                                                    {:source-paths ["src-backend/cljs-prod"]
                                                     :compiler     {:optimizations :simple
                                                                    :pretty-print  false}}}}}}
  :aliases {"dev"  ["do" "clean," "with-profile" "backend-dev" "cljsbuild" "once" "backend," "with-profile" "frontend-dev" "figwheel"]
            "prod" ["do" "clean," "with-profile" "backend-prod" "cljsbuild" "once" "backend," "with-profile" "frontend-prod" "figwheel"]})
