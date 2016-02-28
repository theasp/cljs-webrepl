;; Based on:
;; https://github.com/kanaka/cljs-bootstrap/blob/master/src/cljs_bootstrap/core.cljs
(ns cljs-webrepl.repl
  (:require-macros
   [cljs.env.macros :refer [with-compiler-env]])
  (:require
   [cljs.js :as cljs]
   [cljs.tagged-literals :as tags]
   [cljs.tools.reader :as r]
   [cljs.analyzer :as ana]
   [cljs.repl :as repl]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defonce st (cljs/empty-state))

;; Does not fully work yet
(def ^:dynamic *lib-base-path* "src/")
(def ^:dynamic *file-extensions*
  {nil  [".cljs" ".cljc" ".js"]
   true [".clj" ".cljc"]})

;; Setup source/require resolution
(defn set-load-cfg [& {:keys [lib-base-path]}]
  (when lib-base-path (set! *lib-base-path* lib-base-path))
  nil)

(defn get-file* [extensions {:keys [path] :as cfg} cb]
  ;;(prn :get-file* :extensions extensions :path path)
  (let [file (str *lib-base-path* path (first extensions))]
    ;; browser: read file using XHR
    (let [url (str (.. js/window -location -origin) "/" file)
          req (doto (js/XMLHttpRequest.)
                (.open "GET" url))]
      (set! (.-onreadystatechange req)
            (fn []
              (when (= 4 (.-readyState req))
                (if (= 200 (.-status req))
                  (let [src (.. req -responseText)]
                    (cb {:lang :clj :source src}))
                  (let [emsg (str "XHR load failed:" (.-status req))]
                                        ;(.error js/console emsg)
                                        ;(cb nil)
                    (if (seq extensions)
                      (get-file* (next extensions) cfg cb)
                                        ;(throw (js/Error. emsg))
                                        ;(cb (.error js/console emsg))
                      (cb nil)
                      ))))))
      (.send req))))

(defn native-load [{:keys [macros] :as cfg} cb]
  (get-file* (get *file-extensions* macros) cfg cb))

(defn init [& init-opts]
  ;; Setup source/require resolution
  (apply set-load-cfg init-opts)
  ;; Create cljs.user
  (set! (.. js/window -cljs -user) #js {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Begin section based on Planck core

(defonce current-ns (atom 'cljs.user))

(defonce app-env (atom nil))

(defn map-keys [f m]
  (reduce-kv (fn [r k v] (assoc r (f k) v)) {} m))

(defn ^:export init-app-env [app-env]
  (reset! app-env (map-keys keyword (cljs.core/js->clj app-env))))

(defn repl-read-string [line]
  (r/read-string {:read-cond :allow :features #{:cljs}} line))

(defn ^:export is-readable? [line]
  (binding [r/*data-readers* tags/*cljs-data-readers*]
    (try
      (repl-read-string line)
      true
      (catch :default _
        false))))

(defn ns-form? [form]
  (and (seq? form) (= 'ns (first form))))

(def repl-specials '#{in-ns require require-macros doc})

(defn repl-special? [form]
  (and (seq? form) (repl-specials (first form))))

(def repl-special-doc-map
  '{in-ns          {:arglists ([name])
                    :doc      "Sets *cljs-ns* to the namespace named by the symbol, creating it if needed."}
    require        {:arglists ([& args])
                    :doc      "Loads libs, skipping any that are already loaded."}
    require-macros {:arglists ([& args])
                    :doc      "Similar to the require REPL special function but only for macros."}
    doc            {:arglists ([name])
                    :doc      "Prints documentation for a var or special form given its name"}})

(defn- repl-special-doc [name-symbol]
  (assoc (repl-special-doc-map name-symbol)
         :name name-symbol
         :repl-special-function true))


(defn resolve
  "Given an analysis environment resolve a var. Analogous to
   clojure.core/resolve"
  [env sym]
  {:pre [(map? env) (symbol? sym)]}
  (try
    (ana/resolve-var env sym
                     (ana/confirm-var-exists-throw))
    (catch :default _
      (ana/resolve-macro-var env sym))))

(defn ^:export get-ns [{:keys [current-ns]}]
  (str @current-ns))

(defn extension->lang [extension]
  (if (= ".js" extension)
    :js
    :clj))

(defn require [macros-ns? sym reload]
  (cljs.js/require
   {:*compiler*     st
    :*data-readers* tags/*cljs-data-readers*
    :*load-fn*      native-load
    :*eval-fn*      cljs/js-eval}
   sym
   reload
   {:macros-ns macros-ns?
    :verbose   (:verbose @app-env)}
   (fn [res]
     (println "require result:" res))))

(defn require-destructure [macros-ns? args]
  (let [[[_ sym] reload] args]
    (require macros-ns? sym reload)))

(defn ^:export run-main [main-ns args]
  (let [main-args (js->clj args)]
    (require false (symbol main-ns) nil)
    (cljs/eval-str st
                   (str "(var -main)")
                   nil
                   {:ns         (symbol main-ns)
                    :load       native-load
                    :eval       cljs/js-eval
                    :source-map false
                    :context    :expr}
                   (fn [{:keys [ns value error] :as ret}]
                     (apply value args)))
    nil))

(defn print-error [out-ch error]
  (let [cause (.-cause error)]
    (if cause
      (do
        (println (.-message cause))
        (println (.-stack cause)))
      (do
        (println (.-message error))
        (println (.-stack error))))))

(defn ^:export read-eval-print
  ([repl source cb]
   (read-eval-print repl source true cb))
  ([{:keys [current-ns app-env] :as repl} source expression? cb]
   (binding [ana/*cljs-ns*    @current-ns
             *ns*             (create-ns @current-ns)
             r/*data-readers* tags/*cljs-data-readers*]
     (try
       (let [expression-form (and expression? (repl-read-string source))]
         (if (repl-special? expression-form)
           (let [env (assoc (ana/empty-env) :context :expr
                            :ns {:name @current-ns})]
             (case (first expression-form)
               in-ns
               (reset! current-ns (second (second expression-form)))

               require
               (require-destructure false (rest expression-form))

               require-macros
               (require-destructure true (rest expression-form))

               doc
               (if (repl-specials (second expression-form))
                 (repl/print-doc (repl-special-doc (second expression-form)))
                 (repl/print-doc
                  (let [sym (second expression-form)
                        var (with-compiler-env st
                              (resolve env sym))]
                    (:meta var)))))
             (cb true nil))
           (cljs/eval-str
            st
            source
            (if expression? source "File")
            (merge
             {:ns         @current-ns
              :load       native-load
              :eval       cljs/js-eval
              :source-map false
              :verbose    (:verbose @app-env)}
             (when expression?
               {:context       :expr
                :def-emits-var true}))
            (fn [{:keys [ns value error] :as ret}]
              (if expression?
                (if-not error
                  (do
                    (when-not
                        (or ('#{*1 *2 *3 *e} expression-form)
                            (ns-form? expression-form))
                      (set! *3 *2)
                      (set! *2 *1)
                      (set! *1 value))
                    (reset! current-ns ns)
                    (cb true value))
                  (do
                    (set! *e error)
                    (cb false error)))
                (when error
                  (cb false error)))))))
       (catch :default e
         (cb false e))))))


(defn safe-eval [repl s]
  (read-eval-print repl s (fn [ok result] {:ok     ok
                                          :result result})))

(defn new-repl []
  {:current-ns (atom 'cljs.user)
   :app-env    (atom nil)})
