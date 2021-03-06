(ns cljs-webrepl.core
  (:require
   [clojure.string :as str]
   [cljs.core.async :refer [chan close! timeout put!]]
   [cljsjs.clipboard :as clipboard]
   [reagent.core :as r :refer [atom]]
   [fipp.edn :as fipp]
   [cljs-webrepl.repl-thread :as repl-thread]
   [ca.gt0.theasp.reagent-mdl :as mdl]
   [cljs-webrepl.editor :as editor]
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
  (when-let [dialog (.querySelector js/document (str "#" id))]
    (when-not (.-showModal dialog)
      (.registerDialog js/dialogPolyfill dialog))
    (.showModal dialog)))

(defn show-reset-dialog []
  (show-dialog "reset-dialog"))

(defn show-about-dialog []
  (show-dialog "about-dialog"))

(defn pprint-str [data]
  (-> (with-out-str (fipp/pprint data))
      (str/trim-newline)))

(defn unescape-string [data]
  (-> (println-str data)
      (str/trim-newline)))

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
                      (assoc :ns ns :ready? true)
                      (update-in [:history num] assoc :result result)))
    (swap! state assoc :ns ns :ready? true)))

(defn on-repl-print [state num [s]]
  (when num
    (swap! state update-in [:history num] update :output str s)))

(defn on-repl-error [state num [err]]
  (when num
    (swap! state update-in [:history num] assoc :error err)))

(defn on-repl-crash [state num [err]]
  (swap! state assoc :crashed? true :ready? false)
  (show-reset-dialog))

(defn on-repl-event [state [name num & value]]
  (condp = name
    :repl/eval       (on-repl-eval state num value)
    :repl/result     (on-repl-result state num value)
    :repl/error      (on-repl-error state num value)
    :repl/print      (on-repl-print state num value)
    :webworker/error (on-repl-crash state num value)
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
    (swap! state assoc :running? false :ready? false :crashed? false)
    (close! to-eval)
    (close! to-repl)))

(defn reset-repl! [state]
  (when-let [{:keys [from-repl to-repl]} (:repl @state)]
    (swap! state assoc :running? false :ready? false :crashed? false)
    (close! from-repl)
    (close! to-repl))

  (let [{:keys [to-repl from-repl]} (repl-thread/repl-thread)
        to-eval                     (chan)]
    (swap! state #(-> %
                      (merge default-state)
                      (assoc :running? true :crashed? false :ready? false)
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
             :input (:expression (get history (- c new-cursor) "")))
      (assoc state :input ""))))

(defn clear-input [state]
  (assoc state
         :cursor 0
         :input ""))

(defn eval-input [state input]
  (let [expression (str/trim input)]
    (when-not (str/blank? expression)
      (eval-str! expression)
      (swap! state assoc :ready? false :input "" :cursor 0))))

(defn input-on-change [state value]
  (assoc state :cursor 0 :input value))

(defn scroll [node]
  (let [node (r/dom-node node)]
    (aset node "scrollTop" (.-scrollHeight node))))

(defn scroll-on-update
  [child]
  (r/create-class
   {:display-name        "scroll-on-update"
    :component-did-mount scroll
    :reagent-render      identity}))

(defn history-card-menu [props {:keys [num ns expression result output] :as history-item}]
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
       {:data-clipboard-text (pr-str (:value result))}
       "Copy Result"]]]]])

(defn history-card-expression [props ns expression]
  [:div.mdl-card__title
   [:div
    [:code.CodeMirror-lines (str ns "=>")]]
   [:div
    [editor/code expression]]])

(defn history-card-output [props output]
  [:div
   [:div.card-data.output
    [editor/text (str/trim-newline output)]]
   [:hr.border]])

(defn render-value [value]
  (cond
    (string? value)
    [editor/code (-> value unescape-string pprint-str)]

    (map? value)
    (case (:type value)
      :hiccup (:content value)
      [editor/code (pprint-str value)])

    :default
    [editor/code (pprint-str value)]))

(defn render-error [error]
  [:pre.error
   (or (:stack error)
       (if (and (:message error) (not= "ERROR" (:message error)))
         (:message error)
         (str (:cause error))
         #_(or (str (:cause error)) (str error))))])


(defn render-progress []
  [mdl/upgrade
   [:div.mdl-progress.mdl-js-progress.mdl-progress__indeterminate {:style {:width "100%"}}]])

(defn history-card-result [props {:keys [success? value error] :as result}]
  [:div.card-data.result
   (if result
     (if success?
       [render-value value]
       [render-error error])
     [render-progress])])

(defn history-card [{:keys [state columns] :as props} {:keys [ns expression result output] :as history-item}]
  [:div
   {:class (str "mdl-cell " (card-size-class columns))}
   [:div.mdl-card.mdl-shadow--2dp
    [history-card-expression props ns expression]
    [history-card-menu props history-item]
    [:hr.border]
    (when (seq output)
      [history-card-output props output])
    [history-card-result props result]]])

(defn please-wait [props]
  [:div.history
   [:div.mdl-grid
    [:div.mdl-cell.mdl-cell--12-col
     [:p "REPL initializing..."]]]])

(defn run-button [{:keys [state on-submit ready?] :as props}]
  (let [disabled? (or ready? (str/blank? (:input @state)))]
    [:div.padding-left
     ^{:key disabled?}
     [mdl/upgrade
      [:button.mdl-button.mdl-js-button.mdl-button--fab.mdl-js-ripple-effect.mdl-button--colored
       {:disabled disabled?
        :on-click #(on-submit (:input @state))}
       [:i.material-icons "send"]]]]))

(defn repl-input [{:keys [state] :as props}]
  (let [on-change #(swap! state assoc :input %)
        props     (assoc props :on-change on-change)]
    (fn []
      [mdl/upgrade
       [:div.input-field.mdl-textfield.mdl-js-textfield.mdl-textfield--floating-label
        [:div.mdl-textfield__label {:for "repl-input"}
         (str (:ns @state) "=>")]
        [:div.mdl-textfield__input {:id "repl-input"}
         [editor/editor props (:input @state)]]]])))

(defn input-card [props num]
  [:div.mdl-cell.mdl-cell--12-col
   [:div.mdl-card.mdl-shadow--2dp
    [:div.card-data.expression
     [:div.flex-h
      [repl-input props]
      [run-button props]]]]])


(defn history [props]
  ^{:key (count (:history @state))}
  [scroll-on-update
   [:div.history
    [:div.mdl-grid
     (doall
      (for [[num history-item] (:history @state)]
        ^{:key num}
        [history-card props (assoc history-item :num num)]))
     (when (:ready? @state)
       [input-card props])]]])


(defn reset-dialog [{:keys [state]}]
  [:dialog.mdl-dialog {:id "reset-dialog"}
   (when (:crashed? @state)
     [:div.mdl-dialog__title
      [:p "The REPL has crashed!"]])
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

(defn crash-dialog [{:keys [state]}]
  [:dialog.mdl-dialog {:id "crash-dialog"}
   [:div.mdl-dialog__content
    [:p "The REPL has crashed!"]]
   [:div.mdl-dialog__actions
    [:button.mdl-button
     {:on-click (fn []
                  (close-dialog "crash-dialog")
                  (reset-repl! state))}
     "Reset"]
    [:button.mdl-button
     {:on-click #(close-dialog "crash-dialog")}
     "Cancel"]]])

(defn about-dialog [props]
  [:dialog.mdl-dialog.mdl-dialog__wide {:id "about-dialog"}
   [:h4.mdl-dialog__title (str "CLJS-WebREPL")]
   [:div.mdl-dialog__content
    [:p "A ClojureScript browser based REPL"]
    [:p (str "Using ClojureScript version " *clojurescript-version*)]
    [:p
     "Running: " (if (:running? @state) "Yes" "No") [:br]
     "Ready: " (if (:ready? @state) "Yes" "No") [:br]
     "Crashed: " (if (:crashed? @state) "Yes" "No")]
    [:h5 "License"]
    [:p
     "Copyright © 2016 Andrew Phillips, Dan Holmsand, Mike Fikes, David Nolen, Rich Hickey, Joel Martin & Contributors"]
    [:p
     "Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version."]]
   [:div.mdl-dialog__actions
    [:button.mdl-button
     {:on-click #(close-dialog "about-dialog")}
     "Ok"]]])

(defn reset-repl-button [props]
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

(defn about-menu-item [props]
  [:li.mdl-menu__item {:on-click show-about-dialog} "About"])

(defn reset-menu-item [props]
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
  (let [input (r/cursor state [:input])
        props {:state        state
               :input        input
               :on-submit    #(eval-input state %)
               :history-prev #(swap! state history-prev)
               :history-next #(swap! state history-next)
               :more-columns #(swap! state more-columns)
               :less-columns #(swap! state less-columns)
               :github       #(set! (.-location js/window) "https://github.com/theasp/cljs-webrepl")
               :title-icon   "images/cljs-white.svg"
               :title        "Cljs-WebREPL"}]
    (r/render [home-page props] (.getElementById js/document "app"))))

(defn init! []
  (reset-repl! state)
  (mount-root))
