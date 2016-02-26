(ns cljs-webrepl.doo-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [cljs-webrepl.core-test]))

(doo-tests 'cljs-webrepl.core-test)
