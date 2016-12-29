(ns cljs-webrepl.repl-thread
  (:require
   [cljs.core.async :refer [chan close! timeout put! pipe]]
   [cognitect.transit :as transit]
   [cljs.tools.reader :as reader]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(def script-name "js/backend.js")

(def transit-writer (transit/writer :json))
(def transit-reader (transit/reader :json))

(defn write-transit [data]
  (transit/write transit-writer data))

(defn read-transit [data]
  (transit/read transit-reader data))

(defn write-edn [s]
  (binding [*print-level*  nil
            *print-length* nil
            *print-dup*    true]
    (pr-str s)))

(extend-type js/Error
  IPrintWithWriter
  (-pr-writer [obj writer opts] (write-all writer "#error \"" (str obj) "\"")))


(def edn-readers {'js    #(clj->js %)
                  'uuid  #(when (string? %)
                            (uuid %))
                  'inst  #(when (string? %)
                            (js/Date. %))
                  'queue #(when (vector? %)
                            (into cljs.core.PersistentQueue.EMPTY %))})

(defn read-edn [s]
  (binding [reader/*default-data-reader-fn* (fn [tag value] value)
            reader/*data-readers*           edn-readers]
    (reader/read-string s)))

(defn worker? []
  (nil? js/self.document))

(def thread-type (if (worker?)
                   :worker
                   :master))

(defn read-transit-message [message]
  (let [message (aget message "content")]
    (try
      (read-transit message)
      (catch js/Error e
        (errorf "read-transit-message: %s %s: %s" thread-type (:message e) message)
        [:webworker/error nil e]))))

(defn write-transit-message [message]
  (try
    (js-obj "format" "transit"
            "content" (write-transit message))
    (catch js/Error e nil)))

(defn read-edn-message [message]
  (let [message (aget message "content")]
    (try
      (read-edn message)
      (catch js/Error e
        (errorf "read-edn-message: %s %s %s" thread-type e message)
        [:webworker/error nil e]))))

(defn write-edn-message [message]
  (try
    (js-obj "format" "edn"
            "content" (write-edn message))
    (catch js/Error e nil)))

(defn write-message [message]
  #_(debugf "write-message: %s %s" thread-type (pr-str message))
  (or (write-transit-message message)
      (write-edn-message message)
      (write-transit-message [:webworker/error nil (str "Unable to format message: " (pr message))])))

(defn read-message [message]
  #_(debugf "read-message: %s %s" thread-type (pr-str message))
  (case (.-format message)
    "json"    (read-transit-message message)
    "transit" (read-transit-message message)
    "edn"     (read-edn-message message)
    [:webworker/error nil (str "Unknown message format: " (.-format message))]))

(defn post-message [target message]
  (let [message (write-message message)]
    (debugf "post-message: %s %s" thread-type (pr-str message))
    (.postMessage target message)))

(defn on-message [output-ch message]
  #_(debugf "on-message: %s %s" thread-type (pr-str message))
  (if message
    (put! output-ch (read-message message))
    (warnf "on-message: No message %s" thread-type)))

(defn on-error [output-ch err]
  (errorf "on-error: %s %s" thread-type (pr-str err))
  (put! output-ch [:webworker/error nil (pr-str err)]))

(defn- async-worker [& [target close-fn]]
  (let [is-worker? (not (some? target))
        target     (or target js/self)
        input-ch   (chan)
        output-ch  (chan)

        finally-fn (fn []
                     (debugf "Cleaning up WebWorker: %s" thread-type)
                     (close! input-ch)
                     (close! output-ch)
                     (when close-fn
                       (close-fn)))
        recv-fn    (fn [event]
                     (on-message output-ch (aget event "data")))
        error-fn   (fn [err]
                     (on-error output-ch err)
                     (finally-fn))]
    (.addEventListener target "message" recv-fn)
    (.addEventListener target "error" error-fn)
    (go
      (loop []
        (when-let [message (<! input-ch)]
          (post-message target message)
          (recur)))
      (finally-fn))
    {:input-ch  input-ch
     :output-ch output-ch}))

(defn start-frontend [script-url]
  (let [worker                       (new js/Worker script-url)
        close-fn                     #(.terminate worker)
        {:keys [input-ch output-ch]} (async-worker worker close-fn)]
    {:to-worker   input-ch
     :from-worker output-ch}))

(defn start-backend []
  (let [{:keys [input-ch output-ch]} (async-worker)]
    {:to-worker   output-ch
     :from-worker input-ch}))

(defn repl-thread []
  (let [{:keys [to-worker from-worker]} (start-frontend script-name)]
    {:to-repl   to-worker
     :from-repl from-worker}))

(defn worker [{:keys [to-repl from-repl] :as repl}]
  (let [{:keys [to-worker from-worker] :as worker} (start-backend)]
    (pipe to-worker to-repl)
    (pipe from-repl from-worker)))
