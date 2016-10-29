(ns cljs-webrepl.backend
  (:require
   [cljs-webrepl.repl-thread :as repl-thread]
   [cljs-webrepl.repl :as repl]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(repl-thread/worker (repl/repl-chan-pair))
