(ns ^:figwheel-no-load cljs-webrepl.frontend
  (:require
   [cljs-webrepl.core :as core]
   [figwheel.client :as figwheel :include-macros true]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(enable-console-print!)
(timbre/set-level! :trace)

(figwheel/watch-and-reload
 :websocket-url    "wss://figwheel.industrial.gt0.ca/figwheel-ws"
 :jsload-callback core/mount-root)

(core/init!)
