(ns ^:figwheel-no-load cljs-webrepl.backend
  (:require
   [cljs-webrepl.repl-thread :as repl-thread]
   [cljs-webrepl.repl :as repl]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(enable-console-print!)
(timbre/set-level! :trace)

(repl-thread/worker (repl/repl-chan-pair))
