(ns cljs-webrepl.frontend
  (:require [cljs-webrepl.core :as core]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(core/init!)
