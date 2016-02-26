(ns cljs-webrepl.mdl
  (:require
   [reagent.core :as r]
   [cljsjs.material :as material]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defn upgrade-element
  "Upgrade an element using MDL's componentHandler"
  [node]
  (.upgradeElements js/componentHandler (r/dom-node node)))

(defn downgrade-element
  "Upgrade an element using MDL's componentHandler"
  [node]
  (.downgradeElements js/componentHandler (r/dom-node node)))

(defn upgrade
  "Returns a reagent class that upgrades a child element using MDL's
  componentHandler.

  Example:
  [mdl/upgrade [:button.mdl-button.mdl-js-button \"Hi\"]]"
  [child]
  (r/create-class
   {:display-name        "mdl-upgrade"
    :component-did-mount upgrade-element
    :reagent-render      (fn [child] child)}))
