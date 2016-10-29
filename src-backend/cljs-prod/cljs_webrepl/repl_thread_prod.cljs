(ns cljs-webrepl.repl-thread-prod
  (:require [cljs-webrepl.repl-thread :as repl-thread]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(repl-thread/worker)
