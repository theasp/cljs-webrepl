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

(defn replumb-init [repl-opts]
  (replumb/read-eval-call repl-opts identity "true"))

(defn replumb-async [expression repl-opts out num]
  (let [print-fn  #(put! out [:print num %])
        result-fn #(put! out [:result num (replumb-repl/current-ns) %])]
    (go
      (binding [cljs.core/*print-newline* true
                cljs.core/*print-fn*      print-fn]
        (put! out [:init num (replumb-repl/current-ns) expression])
        (replumb/read-eval-call repl-opts result-fn expression)))))

(defn repl-chan-pair
  ([]
   (repl-chan-pair default-repl-opts))
  ([repl-opts]
   (let [in  (chan)
         out (chan)]
     (go
       (replumb-async "true" repl-opts out nil)
       (loop [num 0]
         (when-let [expression (<! in)]
           (replumb-async expression repl-opts out num)
           (recur (inc num))))
       (close! in)
       (close! out))
     {:in in :out out})))
