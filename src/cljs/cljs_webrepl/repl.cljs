(ns cljs-webrepl.repl
  (:require
   [clojure.string :as str :refer [blank? trim]]
   [cljs.core.async :refer [chan close! timeout put!]]
   [cljs-webrepl.io :as replumb-io]
   [replumb.core :as replumb]
   [replumb.repl :as replumb-repl]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(def default-repl-opts
  (merge (replumb/options :browser
                          ["/src/cljs" "/js/compiled/out"]
                          replumb-io/fetch-file!)
         {:no-pr-str-on-value false
          :warning-as-error   true
          :verbose            false}))

(def current-ns replumb-repl/current-ns)

(defn replumb-async [expression repl-opts from-repl num]
  (let [print-fn  #(put! from-repl [:print num %])
        result-fn #(put! from-repl [:result num (replumb-repl/current-ns) %])]
    (go
      (binding [cljs.core/*print-newline* true
                cljs.core/*print-fn*      print-fn]
        (put! from-repl [:init num (replumb-repl/current-ns) expression])
        (replumb/read-eval-call repl-opts result-fn expression)))))

(defn repl-chan-pair
  ([]
   (repl-chan-pair default-repl-opts))
  ([repl-opts]
   (let [to-repl   (chan)
         from-repl (chan)]
     (go
       (replumb-async "true" repl-opts from-repl nil)
       (loop [num 0]
         (when-let [expression (<! to-repl)]
           (replumb-async expression repl-opts from-repl num)
           (recur (inc num))))
       (close! from-repl)
       (close! to-repl))
     {:to-repl to-repl :from-repl from-repl})))
