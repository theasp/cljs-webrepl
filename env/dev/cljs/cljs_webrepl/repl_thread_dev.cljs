(ns ^:figwheel-no-load cljs-webrepl.repl-thread-dev
  (:require [cljs-webrepl.repl-thread :as repl-thread]))

(enable-console-print!)

(repl-thread/worker)
