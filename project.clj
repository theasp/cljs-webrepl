(defproject cljs-webrepl "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]
                 [com.cognitect/transit-cljs "0.8.239"]
                 [com.taoensso/timbre "4.7.4"]
                 [figwheel "0.5.8"]
                 [fipp "0.6.7"]
                 [hiccup "1.0.5"]
                 [lein-doo "0.1.7"]
                 [reagent "0.6.0"]
                 [reagent-utils "0.2.0"]
                 [replumb "0.2.4"]
                 [com.cemerick/piggieback "0.2.1"]]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-asset-minifier "0.2.7"]]

  :min-lein-version "2.5.0"
  :resource-paths ["resources" "target/cljsbuild"]
  :minify-assets  {:assets {"resources/public/css/site.min.css" "resources/public/css/site.css"}}

  :profiles {:frontend {:dependencies [[ca.gt0.theasp/reagent-mdl "0.1.0-SNAPSHOT"]
                                       [cljsjs/clipboard "1.5.13-0"]
                                       [cljsjs/material "1.2.1-0"]
                                       [cljsjs/codemirror "5.21.0-1"]]

                        :clean-targets ^{:protect false} [:target-path
                                                          [:cljsbuild :builds :frontend-dev :compiler :output-to]
                                                          [:cljsbuild :builds :frontend-dev :compiler :output-dir]
                                                          [:cljsbuild :builds :frontend-min :compiler :output-to]]

                        :cljsbuild {:builds {:frontend-dev
                                             {:source-paths ["src-frontend/cljs" "src-frontend/cljs-dev" "src-shared/cljs" ]
                                              :compiler     {:output-to      "target/cljsbuild/public/js/frontend.js"
                                                             :output-dir     "target/cljsbuild/public/js/frontend"
                                                             :source-map     true
                                                             :asset-path     "js/frontend"
                                                             :main           cljs-webrepl.frontend
                                                             :optimizations  :none
                                                             :pretty-print   true
                                                             :parallel-build true}
                                              :figwheel     {:websocket-url "wss://figwheel.industrial.gt0.ca/figwheel-ws"}}

                                             :frontend-min
                                             {:source-paths ["src-frontend/cljs" "src-frontend/cljs-prod" "src-shared/cljs" ]
                                              :compiler     {:output-to      "target/cljsbuild/public/js/frontend.min.js"
                                                             :asset-path     "js/frontend"
                                                             :main           cljs-webrepl.frontend
                                                             :optimizations  :advanced
                                                             :pretty-print   false
                                                             :parallel-build true}}}}

                        :plugins [[lein-figwheel "0.5.8"]]

                        :figwheel {:http-server-root "public"
                                   :server-port      3449
                                   :nrepl-port       7001
                                   :css-dirs         ["resources/public/css"]}}

             :backend {:clean-targets ^{:protect false} [:target-path
                                                         [:cljsbuild :builds :backend-dev :compiler :output-to]
                                                         [:cljsbuild :builds :backend-dev :compiler :output-dir]
                                                         [:cljsbuild :builds :backend-dev :compiler :source-map]
                                                         [:cljsbuild :builds :backend-min :compiler :output-to]]

                       :cljsbuild {:builds {:backend-dev
                                            {:source-paths ["src-backend/cljs" "src-backend/cljs-dev" "src-shared/cljs"]
                                             :compiler     {:output-to      "target/cljsbuild/public/js/backend.js"
                                                            :output-dir     "target/cljsbuild/public/js/backend"
                                                            :source-map     "target/cljsbuild/public/js/backend.js.map"
                                                            :asset-path     "js/backend"
                                                            :main           cljs-webrepl.backend
                                                            :static-fns     true
                                                            :optimizations  :whitespace
                                                            :pretty-print   true
                                                            :parallel-build true}}

                                            :backend-min
                                            {:source-paths ["src-backend/cljs" "src-backend/cljs-prod" "src-shared/cljs"]
                                             :compiler     {:output-to      "target/cljsbuild/public/js/backend.min.js"
                                                            :asset-path     "js/backend"
                                                            :main           cljs-webrepl.backend
                                                            :static-fns     true
                                                            :optimizations  :simple
                                                            :pretty-print   false
                                                            :parallel-build true}}}}}}

  :aliases {"clean"
            ["do" "with-profile" "backend" "clean," "with-profile" "frontend" "clean"]

            "build"
            ["do" "with-profile" "backend" "cljsbuild" "once," "with-profile" "frontend" "cljsbuild" "once"]

            "build-dev"
            ["do" "with-profile" "backend" "cljsbuild" "once" "backend-dev," "with-profile" "frontend" "cljsbuild" "once" "frontend-dev"]

            "build-backend-dev"
            ["do" "with-profile" "backend" "cljsbuild" "auto" "backend-dev,"]

            "build-min"
            ["do" "with-profile" "backend" "cljsbuild" "once" "backend-min," "with-profile" "frontend" "cljsbuild" "once" "frontend-min"]

            "figwheel"
            ["do" "with-profile" "backend" "cljsbuild" "once," "with-profile" "frontend" "figwheel"]})
