(ns cljs-webrepl.core
  (:require
   [clojure.string :as str :refer [blank? trim]]
   [cljs.core.async :refer [chan close! timeout put!]]
   [cljs-webrepl.repl :as repl]
   [reagent.core :as r :refer [atom]]
   [reagent.session :as session]
   [cljs.pprint :refer [pprint]]
   [cljsjs.clipboard :as clipboard]
   [cljs-webrepl.mdl :as mdl]
   [cljs-webrepl.syntax :refer [syntaxify]]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]))

(defonce state
  (atom {:repl-opts repl/default-repl-opts
         :ns        (repl/current-ns)
         :input     "(+ 1 2)"
         :next      0
         :cursor    0
         :history   []}))

(defn new-eval-in-state [{:keys [next] :as state}]
  (-> state
      (assoc :next (inc next))
      (update :history conj {:num    next
                             :output []})))


(defn repl-init-event [path [ns expression]]
  (swap! state update-in path assoc :ns ns :expression expression))

(defn repl-result-event [path [ns result]]
  (swap! state #(-> %
                    (assoc :ns ns)
                    (update-in path assoc :result result))))

(defn repl-output-event [path [s]]
  (swap! state update-in path update :output conj s))

(defn handle-replumb-event [path event]
  (let [name  (first event)
        value (rest event)]
    (condp = name
      :init   (repl-init-event path value)
      :result (repl-result-event path value)
      :print  (repl-output-event path value)
      (warnf "Unknown repl event: %s" event))))

(defn dispatch-replumb-events [result-chan]
  (let [ch-num (:next (swap! state new-eval-in-state))
        path   [:history (dec ch-num)]]
    (go-loop []
      (when-let [event (<! result-chan)]
        (handle-replumb-event path event)
        (recur)))))

(defn eval-str! [expression]
  (some-> expression
          (trim)
          (repl/replumb-async (:repl-opts @state))
          (dispatch-replumb-events)))

(defn clipboard [child]
  (let [clipboard-atom (atom nil)]
    (r/create-class
     {:display-name "clipboard-button"
      :component-did-mount
      #(let [clipboard (new js/Clipboard (r/dom-node %))]
         (reset! clipboard-atom clipboard))
      :component-will-unmount
      #(when-not (nil? @clipboard-atom)
         (.destroy @clipboard-atom)
         (reset! clipboard-atom nil))
      :reagent-render
      (fn [child] child)})))

(defn history-prev [{:keys [cursor history] :as state}]
  (let [c          (count history)
        new-cursor (inc cursor)]
    (if (<= new-cursor c)
      (assoc state
             :cursor new-cursor
             :input (:expression (nth history (- c new-cursor))))
      state)))

(defn history-next [{:keys [cursor history] :as state}]
  (let [c          (count history)
        new-cursor (dec cursor)]
    (if (> new-cursor 0)
      (assoc state
             :cursor new-cursor
             :input (:expression (nth history (- c new-cursor))))
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
  #_(debugf "Scroll!")
  (let [node (r/dom-node node)]
    (aset node "scrollTop" (.-scrollHeight node))))

(defn scroll-on-update
  [child]
  (r/create-class
   {:display-name        "scroll-on-update"
    :component-did-mount scroll
    :reagent-render      identity}))

(defn pprint-syntax [value]
  (syntaxify (with-out-str (pprint value))))

(defn history-card-menu [props {:keys [ns num expression result output] :as history-item}]
  [mdl/upgrade
   [:div.mdl-card__menu
    [:button.mdl-button.mdl-js-button.mdl-button--icon.mdl-js-ripple-effect {:id (str "menu-" num)}
     [:i.material-icons "more_vert"]]
    [:ul.mdl-menu.mdl-menu--bottom-right.mdl-js-menu.mdl-js-ripple-effect
     {:for (str "menu-" num)}
     [:li.mdl-menu__item
      {:on-click #(eval-str! expression)}
      "Evaluate Again"]
     [clipboard
      [:li.mdl-menu__item
       {:data-clipboard-text expression}
       "Copy Expression"]]
     (if (seq output)
       [clipboard
        [:li.mdl-menu__item
         {:data-clipboard-text "WHOOPS!"}
         "Copy Output"]]
       [:li.mdl-menu__item
        {:disabled true}
        "Copy Output"])
     [clipboard
      [:li.mdl-menu__item
       {:data-clipboard-text (:value result)}
       "Copy Result"]]]]])

(defn history-card-output [props output]
  [:div.card-outpu
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
  [{:keys [state] :as props} {:keys [ns num expression result output] :as history-item}]
  [:div.mdl-cell.mdl-cell--12-col
   [:div.mdl-card.mdl-shadow--2dp
    [history-card-menu props history-item]
    [:div.card-data.expression
     [:code
      (str num " " ns "=> ")
      (syntaxify expression)]]
    [:hr.border]

    (when (seq output)
      [history-card-output props output])

    [history-card-result props result]]])

(defn history [props]
  (let [state (:state props)]
    ^{:key (:next @state)}
    [scroll-on-update
     [:div.history
      [:div.mdl-grid
       (doall
        (for [history-item (:history @state)]
          ^{:key (:num history-item)}
          [history-card props history-item]))]]]))

(defn input-field [props]
  (let [state (:state props)]
    [:div.input-field
     [:form {:action "#" :autoComplete "off"}
      [mdl/upgrade
       [:div.wide.mdl-textfield.mdl-js-textfield.mdl-textfield--floating-label
        [:input.wide.mdl-textfield__input
         {:type         :text
          :id           "input"
          :autocomplete "off"
          :value        (:input @state)
          :on-change    #(swap! state input-on-change %)
          :on-key-down  #(input-key-down state %)}]
        [:label.mdl-textfield__label {:for "input"}
         (str (:ns @state) "=>")]]]]]))

(defn run-button [props]
  (let [state     (:state props)
        is-blank? (blank? (:input @state))]
    [:div.padding-left
     ^{:key is-blank?}
     [mdl/upgrade
      [:button.mdl-button.mdl-js-button.mdl-button--fab.mdl-js-ripple-effect.mdl-button--colored
       (merge (when is-blank? {:disabled true})
              {:on-click #(eval-input state)})
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
           [:i.material-icons "link"][:span "GitHub"]]
          [:button.mdl-button.mdl-js-button.mdl-button--icon.mdl-js-ripple-effect {:id "main-menu"}
           [:i.material-icons "more_vert"]]
          [:ul.mdl-menu.mdl-menu--bottom-right.mdl-js-menu.mdl-js-ripple-effect
           {:for "main-menu"}
           [:li.mdl-menu__item
            {:on-click #(set! (.-location js/window) "https://github.com/theasp/cljs-webrepl")}
            "GitHub"]
           [:li.mdl-menu__item
            {:on-click show-about-dialog}
            "About"]]]]
        [history props]
        [input props]]]]]))

(defn mount-root []
  (r/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (repl/replumb-init (:repl-opts @state))
  (mount-root))
