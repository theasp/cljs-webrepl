(ns cljs-webrepl.core
  (:require
   [clojure.string :as str]
   [cljs.core.async :refer [chan close! timeout put!]]
   [cljsjs.clipboard :as clipboard]
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
  {:repl    nil
   :ns      nil
   :columns 0
   :input   ""
   :cursor  0
   :history (sorted-map)})

(defonce state (atom default-state))

(def max-columns 4)

(def card-size-class
  ["mdl-cell--12-col mdl-cell--8-col-tablet mdl-cell--6-col-phone"
   "mdl-cell--6-col mdl-cell--4-col-tablet mdl-cell--3-col-phone"
   "mdl-cell--4-col mdl-cell--3-col-tablet mdl-cell--2-col-phone"])

(defn set-columns [state columns]
  (if (get card-size-class columns)
    (assoc state :columns columns)
    state))

(defn more-columns [state]
  (set-columns state (inc (:columns state))))

(defn less-columns [state]
  (set-columns state (dec (:columns state))))

(defn close-dialog [id]
  (some-> (.querySelector js/document (str "#" id))
          (.close)))

(defn show-dialog [id]
  (debugf "Showing dialog: %s" id)
  (when-let [dialog (.querySelector js/document (str "#" id))]
    (when-not (.-showModal dialog)
      (.registerDialog js/dialogPolyfill dialog))
    (.showModal dialog)))

(defn pprint-str [data]
  (with-out-str (fipp/pprint data)))

(defn trigger
  "Returns a reagent class that can be used to easily add triggers
  from the map in `props`, such as :component-did-mount.  See
  `reagent.core/create-class` for more information."
  [props content]
  (r/create-class
   (-> {:display-name "trigger"}
       (merge props)
       (assoc :reagent-render (fn [_ content] content)))))

(defn clipboard [child]
  (let [clipboard-atom (atom nil)]
    (r/create-class
     {:display-name           "clipboard-button"
      :component-did-mount    (fn [node]
                                (let [clipboard (new js/Clipboard (r/dom-node node))]
                                  (reset! clipboard-atom clipboard)))
      :component-will-unmount (fn []
                                (when-not (nil? @clipboard-atom)
                                  (.destroy @clipboard-atom)
                                  (reset! clipboard-atom nil)))
      :reagent-render         (fn [child] child)})))

(defn focus-node [node]
  (-> (r/dom-node node)
      (.focus)))

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
    (swap! state update-in [:history num] update :output str s)))

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
  (when-let [{:keys [from-repl to-repl]} (:repl @state)]
    (close! from-repl)
    (close! to-repl))

  (let [{:keys [to-repl from-repl]} (repl-thread/repl-thread)
        to-eval                     (chan)]
    (swap! state #(-> %
                      (merge default-state)
                      (assoc :repl {:to-repl   to-eval
                                    :from-repl from-repl})))
    (repl-event-loop state from-repl)
    (repl-eval-loop to-eval to-repl from-repl)))

(defn eval-str! [expression]
  (let [{:keys [to-repl]} (:repl @state)
        expression        (some-> expression str/trim)]
    (when (and (some? to-repl) (some? expression))
      (put! to-repl expression))))

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
  (let [input (str/trim (:input @state))]
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
     [clipboard
      [:li.mdl-menu__item
       {:data-clipboard-text expression}
       "Copy Expression"]]
     (if (some? output)
       [clipboard
        [:li.mdl-menu__item
         {:data-clipboard-text output}
         "Copy Output"]]
       [:li.mdl-menu__item
        {:disabled true}
        "Copy Output"])
     [clipboard
      [:li.mdl-menu__item
       {:data-clipboard-text (if (string? (:value result))
                               (:value result)
                               (pprint-str (:value result)))}
       "Copy Result"]]]]])

(defn history-card-output [props output]
  [:div.card-output
   [:div.card-data
    (into [:pre.line]
          (for [line (str/split output #"\n")]
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
  [{:keys [state columns] :as props} {:keys [num ns expression result output] :as history-item}]
  [:div
   {:class (str "mdl-cell " (card-size-class columns))}
   [:div.mdl-card.mdl-shadow--2dp
    [history-card-menu props num history-item]
    [:div.card-data.expression
     [:code
      (str ns "=> ")
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

(defn input-field [props]
  (let [state              (:state props)
        {:keys [ns input]} @state
        is-init?           (some? ns)
        ns                 (or ns "unknown")]
    [:div.input-field
     [:form {:action "#" "autoComplete" "off"}
      [mdl/upgrade
       [:div.wide.mdl-textfield.mdl-js-textfield.mdl-textfield--floating-label
        [trigger
         {:component-did-mount focus-node}
         [:input.wide.mdl-textfield__input
          {:type         :text
           :id           "input"
           :autocomplete "off"
           :value        input
           :disabled     (not is-init?)
           :on-change    #(swap! state input-on-change %)
           :on-key-down  #(input-key-down state %)}]]
        ^{:key is-init?}
        [:label.mdl-textfield__label {:for "input"}
         (str ns "=>")]]]]]))

(defn run-button [props]
  (let [state              (:state props)
        {:keys [ns input]} @state
        is-init?           (some? ns)
        is-blank?          (str/blank? input)
        is-disabled?       (or (not is-init?) is-blank?)]
    [:div.padding-left
     ^{:key is-disabled?}
     [mdl/upgrade
      [:button.mdl-button.mdl-js-button.mdl-button--fab.mdl-js-ripple-effect.mdl-button--colored
       {:disabled is-disabled?
        :on-click #(eval-input state)}
       [:i.material-icons "send"]]]]))

(defn repl-input [props]
  [:div.input
   [:div.card-data.expression
    [:div.flex-h
     [input-field props]
     [run-button props]]]])

(defn input-card [{:keys [state] :as props} num]
  [:div.mdl-cell.mdl-cell--12-col
   [:div.mdl-card.mdl-shadow--2dp
    [repl-input props]]])

(defn input-ok? [{:keys [history]}]
  (or (= (count history) 0)
      (some? (-> history last second :result))))

(defn history [props]
  ^{:key (count (:history @state))}
  [scroll-on-update
   [:div.history
    [:div.mdl-grid
     (doall
      (for [[num history-item] (:history @state)]
        ^{:key num}
        [history-card props (assoc history-item :num num)]))
     (when (input-ok? @state)
       [input-card props])]]])


(defn reset-dialog [{:keys [state]}]
  [:dialog.mdl-dialog {:id "reset-dialog"}
   [:div.mdl-dialog__content
    [:p "Reset REPL? All data will be lost."]]
   [:div.mdl-dialog__actions
    [:button.mdl-button
     {:on-click (fn []
                  (close-dialog "reset-dialog")
                  (reset-repl! state))}
     "Reset"]
    [:button.mdl-button
     {:on-click #(close-dialog "reset-dialog")}
     "Cancel"]]])

(defn about-dialog [props]
  [:dialog.mdl-dialog.mdl-dialog__wide {:id "about-dialog"}
   [:h4.mdl-dialog__title "CLJS-WebREPL"]
   [:div.mdl-dialog__content
    [:p "A ClojureScript browser based REPL"]
    [:p (str "Using ClojureScript version " *clojurescript-version*)]
    [:h5 "License"]
    [:p
     "Copyright © 2016 Andrew Phillips, Dan Holmsand, Mike Fikes, David Nolen, Rich Hickey, Joel Martin & Contributors"]
    [:p
     "Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version."]]
   [:div.mdl-dialog__actions
    [:button.mdl-button
     {:on-click #(close-dialog "about-dialog")}
     "Ok"]]])

(defn reset-repl-button [{:keys [show-reset-dialog]}]
  [:button.mdl-button.mdl-js-button.mdl-js-ripple-effect
   {:on-click show-reset-dialog
    :style    {:color "#fff"}}
   [:i.material-icons "report"]
   [:span.mdl-cell--hide-phone "RESET"]])

(defn more-columns-button [{:keys [more-columns]}]
  [:button.mdl-button.mdl-js-button.mdl-js-ripple-effect.mdl-cell--hide-phone
   {:on-click more-columns
    :style    {:color "#fff"}}
   [:i.material-icons "view_column"]])

(defn less-columns-button [{:keys [less-columns]}]
  [:button.mdl-button.mdl-js-button.mdl-js-ripple-effect.mdl-cell--hide-phone
   {:on-click less-columns
    :style    {:color "#fff"}}
   [:i.material-icons "view_stream"]])

(defn menu-button [id]
  [:button.mdl-button.mdl-js-button.mdl-button--icon.mdl-js-ripple-effect {:id "main-menu"}
   [:i.material-icons "more_vert"]])

(defn more-columns-menu-item [{:keys [more-columns]}]
  [:li.mdl-menu__item {:on-click more-columns} "More Columns"])

(defn less-columns-menu-item [{:keys [less-columns]}]
  [:li.mdl-menu__item {:on-click less-columns} "Less Columns"])

(defn github-menu-item [{:keys [github]}]
  [:li.mdl-menu__item {:on-click github} "GitHub"])

(defn about-menu-item [{:keys [show-about-dialog]}]
  [:li.mdl-menu__item {:on-click show-about-dialog} "About"])

(defn reset-menu-item [{:keys [show-reset-dialog]}]
  [:li.mdl-menu__item {:on-click show-reset-dialog} "Reset REPL"])

(defn home-page-menu [props]
  [:ul.mdl-menu.mdl-menu--bottom-right.mdl-js-menu.mdl-js-ripple-effect
   {:for "main-menu"}
   [more-columns-menu-item props]
   [less-columns-menu-item props]
   [reset-menu-item props]
   [github-menu-item props]
   [about-menu-item props]])

(defn home-page-title [{:keys [title title-icon] :as props}]
  [:span.mdl-layout-title
   [:img.svg-size {:src title-icon}]
   (str " " title)])

(defn home-page-header [{:keys [reset-repl more-columns] :as props}]
  [:header.mdl-layout__header
   [:div.mdl-layout__header-row
    [home-page-title props]
    [:div.mdl-layout-spacer]
    [reset-repl-button props]
    [less-columns-button props]
    [more-columns-button props]
    [menu-button "main-menu"]
    [home-page-menu props]]])

(defn home-page [{:keys [state] :as props}]
  [:div
   [about-dialog props]
   [reset-dialog props]
   [:div.tall
    [mdl/upgrade
     [:div.flex-v.tall.mdl-layout.mdl-js-layout.mdl-layout--fixed-header.mdl-layout--no-drawer-button
      [home-page-header props]
      (if (:ns @state)
        [history (assoc props :columns (:columns @state))]
        [please-wait props])]]]])

(defn mount-root []
  (let [props {:state             state
               :more-columns      #(swap! state more-columns)
               :less-columns      #(swap! state less-columns)
               :show-reset-dialog #(show-dialog "reset-dialog")
               :show-about-dialog #(show-dialog "about-dialog")
               :github            #(set! (.-location js/window) "https://github.com/theasp/cljs-webrepl")
               :title-icon        "images/cljs-white.svg"
               :title             "Cljs-WebREPL"}]
    (r/render [home-page props] (.getElementById js/document "app"))))

(defn init! []
  (reset-repl! state)
  (mount-root))
