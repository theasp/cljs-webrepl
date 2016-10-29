(ns ^:figwheel-no-load cljs-webrepl.repl-thread-dev
  (:require
   [cljs-webrepl.repl-thread :as repl-thread]
   [cljs-webrepl.repl :as repl]))

(enable-console-print!)

(repl-thread/worker (repl/repl-chan-pair))
