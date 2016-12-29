(ns cljs-webrepl.frontend
  (:require [cljs-webrepl.core :as core]
            [taoensso.timbre :as timbre
             :refer-macros (tracef debugf infof warnf errorf)]))

;;ignore println statements in prod
;;(set! *print-fn* (fn [& _]))

(timbre/set-level! :info)

(core/init!)
