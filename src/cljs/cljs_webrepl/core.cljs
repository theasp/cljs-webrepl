(ns cljs-webrepl.core
  (:require
   [clojure.string :as str :refer [blank? trim]]
   [cljs.core.async :refer [chan close! timeout put!]]
   [reagent.core :as r :refer [atom]]
   [reagent.session :as session]
   [fipp.edn :as fipp]
   [cljs-webrepl.repl :as repl]
   [cljs-webrepl.repl-thread :as repl-thread]
   [cljs-webrepl.mdl :as mdl]
   [cljs-webrepl.syntax :refer [syntaxify]]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(def default-state
  {:ns      nil
   :input   ""
   :cursor  0
   :history (sorted-map)})

(defonce state (atom default-state))

(defn pprint-str [data]
  (with-out-str (fipp/pprint data)))

(defn on-repl-eval [state num [ns expression]]
  (when num
    (swap! state update-in [:history num] assoc :ns ns :expression expression)))

(defn on-repl-result [state num [ns result]]
  (if num
    (swap! state #(-> %
                      (assoc :ns ns)
                      (update-in [:history num] assoc :result result)))
    (swap! state assoc :ns ns)))

(defn on-repl-print [state num [s]]
  (when num
    (swap! state update-in [:history num] update :output conj s)))

(defn on-repl-error [state num [err]]
  (when num
    (swap! state update-in [:history num] assoc :error err)))

(defn on-repl-event [state [name num & value]]
  (condp = name
    :repl/eval   (on-repl-eval state num value)
    :repl/result (on-repl-result state num value)
    :repl/error  (on-repl-error state num value)
    :repl/print  (on-repl-print state num value)
    (warnf "Unknown repl event: %s %s" name value)))

(defn repl-event-loop [state from-repl]
  (go-loop []
    (when-let [event (<! from-repl)]
      (on-repl-event state event)
      (recur))))

(defn repl-eval-loop [to-eval to-repl from-repl]
  (go-loop [num 0]
    (when-let [expression (<! to-eval)]
      (put! from-repl [:repl/eval num (:ns @state) expression])
      (put! to-repl [:repl/eval num expression])
      (recur (inc num)))
    (close! to-eval)
    (close! to-repl)))

(defn reset-repl! [state]
  (when-let [repl (:repl @state)]
    (close! (:from-repl repl))
    (close! (:to-repl repl)))

  (let [{:keys [to-repl from-repl]} (repl-thread/repl-thread)
        to-eval                     (chan)]
    (repl-event-loop state from-repl)
    (repl-eval-loop to-eval to-repl from-repl)
    (swap! state #(-> %
                      (merge default-state)
                      (assoc :repl {:to-repl   to-eval
                                    :from-repl from-repl})))))

(defn eval-str! [expression]
  (let [{:keys [to-repl]} (:repl @state)
        expression        (some-> expression trim)]
    (when (and (some? to-repl) (some? expression))
      (put! to-repl expression))))

(defn copy-to-clipboard [txt]
  (->> (js-obj "dataType" "text/plain" "data" txt)
       (new js/ClipboardEvent "copy")
       (js/document.dispatchEvent)))

(defn history-prev [{:keys [cursor history] :as state}]
  (let [c          (count history)
        new-cursor (inc cursor)]
    (if (<= new-cursor c)
      (assoc state
             :cursor new-cursor
             :input (:expression (get history (- c new-cursor))))
      state)))

(defn history-next [{:keys [cursor history] :as state}]
  (let [c          (count history)
        new-cursor (dec cursor)]
    (if (> new-cursor 0)
      (assoc state
             :cursor new-cursor
             :input (:expression (get history (- c new-cursor))))
      state)))

(defn clear-input [state]
  (assoc state
         :cursor 0
         :input ""))

(defn eval-input [state]
  (let [input (trim (:input @state))]
    (when-not (= "" input)
      (eval-str! input)
      (swap! state assoc :input ""))))

(defn input-key-down [state event]
  (case (.-which event)
    ;; Enter
    13 (do (eval-input state)
           (. event preventDefault))

    ;; Escape
    27 (do (swap! state clear-input)
           (. event preventDefault))

    ;; Tab
    9  (. event preventDefault)

    ;; Up
    38 (do (swap! state history-prev)
           (. event preventDefault))

    ;;Down
    40 (do (swap! state history-next)
           (. event preventDefault))
    nil))

(defn input-on-change [state event]
  (assoc state
         :cursor 0
         :input (-> event .-target .-value)))

(defn scroll [node]
  (let [node (r/dom-node node)]
    (aset node "scrollTop" (.-scrollHeight node))))

(defn scroll-on-update
  [child]
  (r/create-class
   {:display-name        "scroll-on-update"
    :component-did-mount scroll
    :reagent-render      identity}))

(defn pprint-syntax [value]
  (-> value
      (pprint-str)
      (syntaxify)))

(defn history-card-menu [props num {:keys [ns expression result output] :as history-item}]
  [mdl/upgrade
   [:div.mdl-card__menu
    [:button.mdl-button.mdl-js-button.mdl-button--icon.mdl-js-ripple-effect {:id (str "menu-" num)}
     [:i.material-icons "more_vert"]]
    [:ul.mdl-menu.mdl-menu--bottom-right.mdl-js-menu.mdl-js-ripple-effect
     {:for (str "menu-" num)}
     [:li.mdl-menu__item
      {:on-click #(eval-str! expression)}
      "Evaluate Again"]
     [:li.mdl-menu__item
      {:on-click #(copy-to-clipboard (pprint-str output))}
      "Copy Expression"]
     (if (seq output)
       [:li.mdl-menu__item
        {:data-clipboard-text "WHOOPS!"}
        "Copy Output"]
       [:li.mdl-menu__item
        {:disabled true}
        "Copy Output"])
     [:li.mdl-menu__item
      {:on-click #(copy-to-clipboard (if (string? (:value result)) (:value result) (pprint-str (:value result))))}
      "Copy Result"]]]])

(defn history-card-output [props output]
  [:div.card-output
   [:div.card-data
    (into [:pre.line]
          (for [line output]
            [:code.line line]))]
   [:hr.border]])

(defn history-card-result [props {:keys [success? value error] :as result}]
  [:div.card-data.result
   (if (some? result)
     (if success?
       [:code
        [:pre
         (if (string? value)
           value
           (pprint-syntax value))]]
       [:code
        [:pre (pprint-syntax error)]])
     [:code
      [:pre "..."]])])

(defn history-card
  [{:keys [state] :as props} num {:keys [ns expression result output] :as history-item}]
  [:div.mdl-cell.mdl-cell--12-col
   [:div.mdl-card.mdl-shadow--2dp
    [history-card-menu props num history-item]
    [:div.card-data.expression
     [:code
      (str num " " ns "=> ")
      (syntaxify expression)]]
    [:hr.border]

    (when (seq output)
      [history-card-output props output])

    [history-card-result props result]]])

(defn please-wait [props]
  [:div.history
   [:div.mdl-grid
    [:div.mdl-cell.mdl-cell--12-col
     [:p "REPL initializing..."]]]])

(defn history [{:keys [state] :as props}]
  ^{:key (count (:history @state))}
  [scroll-on-update
   [:div.history
    [:div.mdl-grid
     (doall
      (for [[num history-item] (:history @state)]
        ^{:key num}
        [history-card props num history-item]))]]])

(defn input-field [props]
  (let [state              (:state props)
        {:keys [ns input]} @state
        is-init?           (some? ns)
        ns                 (or ns "unknown")]
    [:div.input-field
     [:form {:action "#" "autoComplete" "off"}
      [mdl/upgrade
       [:div.wide.mdl-textfield.mdl-js-textfield.mdl-textfield--floating-label
        [:input.wide.mdl-textfield__input
         {:type         :text
          :id           "input"
          :autocomplete "off"
          :value        input
          :disabled     (not is-init?)
          :on-change    #(swap! state input-on-change %)
          :on-key-down  #(input-key-down state %)}]
        ^{:key is-init?}
        [:label.mdl-textfield__label {:for "input"}
         (str ns "=>")]]]]]))

(defn run-button [props]
  (let [state              (:state props)
        {:keys [ns input]} @state
        is-init?           (some? ns)
        is-blank?          (blank? input)
        is-disabled?       (or (not is-init?) is-blank?)]
    [:div.padding-left
     ^{:key is-disabled?}
     [mdl/upgrade
      [:button.mdl-button.mdl-js-button.mdl-button--fab.mdl-js-ripple-effect.mdl-button--colored
       {:disabled is-disabled?
        :on-click #(eval-input state)}
       [:i.material-icons "send"]]]]))

(defn input [props]
  [:div.input
   [:div.mdl-grid.mdl-shadow--2dp.white-bg
    [:div.mdl-cell--12-col
     [:div.flex-h
      [input-field props]
      [run-button props]]]]])

(defn close-about-dialog []
  (when-let [dialog (.querySelector js/document "#about-dialog")]
    (.close dialog)))

(defn about-dialog []
  [:dialog.mdl-dialog {:id "about-dialog"}
   [:h4.mdl-dialog__title "CLJS-WebREPL"]
   [:div.mdl-dialog__content
    [:p
     (str "A ClojureScript browser based REPL")]
    [:p (str "Using ClojureScript version " *clojurescript-version*)]
    [:h5 "License"]
    [:p
     "Copyright © 2016 Andrew Phillips, Dan Holmsand, Mike Fikes, David Nolen, Rich Hickey, Joel Martin & Contributors"]
    [:p
     "Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version."]]
   [:div.mdl-dialog__actions
    [:button.mdl-button
     {:on-click close-about-dialog}
     "Ok"]]])

(defn show-about-dialog []
  (when-let [dialog (.querySelector js/document "#about-dialog")]
    (when-not (.-showModal dialog)
      (.registerDialog js/dialogPolyfill dialog))
    (.showModal dialog)))

(defn home-page []
  (let [props {:state state
               :title "Cljs-WebREPL"}]
    [:div
     [about-dialog]
     [:div.tall
      [mdl/upgrade
       [:div.flex-v.tall.mdl-layout.mdl-js-layout.mdl-layout--fixed-header.mdl-layout--no-drawer-button
        [:header.mdl-layout__header
         [:div.mdl-layout__header-row
          [:span.mdl-layout-title
           [:img.svg-size {:src "images/cljs-white.svg"}]
           " CLJS-WebREPL"]
          [:div.mdl-layout-spacer]
          [:a.mdl-navigation__link.mdl-navigation__link--icon
           {:href "https://github.com/theasp/cljs-webrepl"}
           [:i.material-icons "link"]
           [:span "GitHub"]]
          [:button.mdl-button.mdl-js-button.mdl-button--icon.mdl-js-ripple-effect {:id "main-menu"}
           [:i.material-icons "more_vert"]]
          [:ul.mdl-menu.mdl-menu--bottom-right.mdl-js-menu.mdl-js-ripple-effect
           {:for "main-menu"}
           [:li.mdl-menu__item
            {:on-click #(reset-repl! state)}
            "Reset REPL"]
           [:li.mdl-menu__item
            {:on-click #(set! (.-location js/window) "https://github.com/theasp/cljs-webrepl")}
            "GitHub"]
           [:li.mdl-menu__item
            {:on-click show-about-dialog}
            "About"]]]]
        (if (:ns @state)
          [history props]
          [please-wait props])
        [input props]]]]]))

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (reset-repl! state)
  (mount-root))
