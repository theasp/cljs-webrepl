(ns cljs-webrepl.repl
  (:require
   [cljs.tools.reader :refer [read-string]]
   [cljs.js :refer [empty-state eval js-eval]]
   [cljs.env :refer [*compiler*]]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defn eval-str [repl s]
  (eval repl
        (read-string s)
        {:eval       js-eval
         :source-map true
         :context    :expr}
        (fn [result] result)))

(defn safe-eval [state s]
  (try
    (eval-str state s)
    (catch js/Error e
      (warnf "Caught exception: %s" e)
      {:exception (.toString e)})))

(defn new-repl []
  (empty-state))
