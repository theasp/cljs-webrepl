(ns cljs-webrepl.backend
  (:require
   [cljs-webrepl.repl-thread :as repl-thread]
   [cljs-webrepl.repl :as repl]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

;;ignore println statements in prod
;;(set! *print-fn* (fn [& _]))
(enable-console-print!)
(timbre/set-level! :info)

(repl-thread/worker (repl/repl-chan-pair))
