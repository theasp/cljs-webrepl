(ns cljs-webrepl.repl-thread
  (:require
   [cljs.core.async :refer [chan close! timeout put! pipe]]
   [cognitect.transit :as t]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(def script-name "js/backend.js")

(defn worker? []
  (nil? js/self.document))

(defn worker-type []
  (if (worker?)
    :worker
    :master))

(defn- deserialize [reader msg]
  (debugf "Deserialize: %s %s" (worker-type) (.-data.msg msg))
  (some->> (.-data.msg msg)
           (t/read reader)))

(defn- serialize [writer msg]
  (debugf "Serialize: %s %s" (worker-type) msg)
  (let [msg (t/write writer msg)]
    (debugf "Serialized: %s %s" (worker-type) msg)
    (js-obj "msg" msg)))

(defn post-message [target writer msg]
  (try
    (.postMessage target (serialize writer msg))
    (catch js/Error e
      (.postMessage target (serialize writer [:webworker/error nil (str e)])))))

(defn- async-worker [& [target close-fn]]
  (let [is-worker? (not (some? target))
        target     (or target js/self)
        input-ch   (chan)
        output-ch  (chan)
        reader     (t/reader :json)
        writer     (t/writer :json)

        finally-fn (fn []
                     (debugf "Cleaning up WebWorker %s" (worker-type))
                     (close! input-ch)
                     (close! output-ch)
                     (when close-fn
                       (close-fn)))

        recv-fn    (fn [msg]
                     (debugf "MSG: %s" msg)
                     (js/console.log msg)
                     (when-let [msg (deserialize reader msg)]
                       (put! output-ch msg)))

        error-fn   (fn [err]
                     (let [err {:message (.-message err)
                                :file    (.-filename err)
                                :line    (.-lineno err)
                                :worker? (worker?)}]
                       (errorf "WebWorker: %s" err)
                       (put! output-ch [:webworker/error nil err])
                       (finally-fn)))]
    (.addEventListener target "message" recv-fn)
    (.addEventListener target "error" error-fn)
    (go
      (loop []
        (when-let [msg (<! input-ch)]
          (post-msg target writer msg)
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
