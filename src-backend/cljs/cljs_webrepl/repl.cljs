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
         {:no-pr-str-on-value true
          :warning-as-error   false
          :verbose            false}))

(def current-ns replumb-repl/current-ns)

(defn native? [obj]
  (or (nil? obj) (boolean? obj) (string? obj) (number? obj) (keyword? obj) (coll? obj)))


(defn obj->map* [obj acc key obj->map]
  (let [value (aget obj key)]
    (cond
      (fn? value)     acc
      (native? value) (assoc acc (keyword key) value)
      (object? value) (obj->map obj)
      :else           (assoc acc (keyword key) value))))

(defn- obj->map
  "Workaround for `TypeError: Cannot convert object to primitive value`s
  caused by `(js->clj (.-body  exp-req) :keywordize-keys true)` apparently
  failing to correctly identify `(.-body exp-req)` as an object. Not sure
  what's causing this problem."
  [o]
  (when o
    (reduce #(obj->map* o %1 %2 obj->map) {} (js-keys o))))

(defn err->map [err]
  (when err
    (errorf "Evaluation: %s" err)
    {:message (.-message err)
     :data    (.-data err)}))

(defn fix-value [value]
  (cond (native? value) value
        (object? value) (obj->map value)
        :default        (str value)))

(defn fix-result [result]
  (-> result
      (update :value fix-value)
      (update :error err->map)))

(defn on-repl-eval [[num expression] from-repl repl-opts]
  (put! from-repl [:repl/eval num (replumb-repl/current-ns) expression])

  (let [print-fn  #(put! from-repl [:repl/print num %])
        result-fn #(put! from-repl [:repl/result num (replumb-repl/current-ns) (fix-result %)])]
    (binding [cljs.core/*print-newline* true
              cljs.core/*print-fn*      print-fn]
      (debugf "REPL: %s" expression)
      ;; Use the 3rd argument to put! so the :init event is sent first
      (replumb/read-eval-call repl-opts result-fn expression))))

(defn repl-loop [from-repl to-repl repl-opts]
  (go
    (put! to-repl [:repl/eval nil "true"])
    (loop []
      (when-let [msg (<! to-repl)]
        (case (first msg)
          :repl/eval (on-repl-eval (rest msg) from-repl repl-opts)
          (warnf "Unknown REPL input event: %s" msg))
        (recur)))
    (close! from-repl)
    (close! to-repl)))

(defn repl-chan-pair
  ([]
   (repl-chan-pair default-repl-opts))
  ([repl-opts]
   (let [to-repl   (chan)
         from-repl (chan)]
     (repl-loop from-repl to-repl repl-opts)
     {:to-repl to-repl :from-repl from-repl})))
