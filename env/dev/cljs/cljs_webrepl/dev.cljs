(ns ^:figwheel-no-load cljs-webrepl.dev
  (:require [cljs-webrepl.core :as core]
            [figwheel.client :as figwheel :include-macros true]))

(enable-console-print!)

(figwheel/watch-and-reload
 :websocket-url    "wss://figwheel.industrial.gt0.ca/figwheel-ws"
 :jsload-callback core/mount-root)

(core/init!)
