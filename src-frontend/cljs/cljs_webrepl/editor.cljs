(ns cljs-webrepl.editor
  (:require
   [reagent.core :as r :refer [atom]]
   [cljsjs.codemirror]
   [cljsjs.codemirror.mode.clojure]
   [cljsjs.codemirror.keymap.emacs]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defn codemirror-did-mount [node editor {:keys [focus? read-only? on-change on-key-down mode]} text]
  (let [element (-> node (r/dom-node))
        opts    (js-obj "readOnly" read-only?
                        "height" "auto"
                        "autofocus" focus?
                        "lineWrapping" true
                        "lineNumbers" false
                        "mode" (or mode "clojure")
                        "value" text
                        "indentUnit" 2
                        "electricChars" true
                        "viewportMargin" js/Infinity)
        editor  (reset! editor (js/CodeMirror.fromTextArea element opts))]
    (when on-change
      (.on editor "change" on-change))
    (when on-key-down
      (.on editor "keydown" on-key-down))))

(defn codemirror-will-update [node editor [_ _ text]]
  (when-let [editor @editor]
    (.setValue editor text)))

(defn codemirror-will-unmount [node editor]
  (when-let [editor @editor]
    true)
  (reset! editor nil))

(defn codemirror-render [_ text]
  [:textarea {:value text :read-only true}])

(defn codemirror [props text]
  (let [editor (atom nil)]
    (r/create-class
     {:display-name           "codemirror"
      :component-did-mount    #(codemirror-did-mount %1 editor props text)
      :component-will-update  #(codemirror-will-update %1 editor %2)
      :component-will-unmount #(codemirror-will-unmount %1 editor)
      :reagent-render         codemirror-render})))

(defn editor-update [{:keys [state] :as props} editor change]
  (swap! state assoc :input (.getValue editor)))

(defn editor-key-enter [{:keys [input submit] :as props} editor event]
  (submit input)
  (.preventDefault event))

(defn editor-key-up [{:keys [history-prev state] :as props} editor event]
  (history-prev)
  (.setValue editor (:input @state))
  (.refresh editor)
  (.preventDefault event))

(defn editor-key-down [{:keys [history-next state] :as props} editor event]
  (history-next)
  (.setValue editor (:input @state))
  (.refresh editor)
  (.preventDefault event))

(def key-names {:enter 13
                :up    38
                :down  40})

(def key-code->name
  (into {} (for [[k v] key-names] [v k])))

(defn editor-key [props editor event]
  (when-let [name (key-code->name (.-keyCode event))]
    (case name
      :enter (when (or (.-ctrlKey event) (not (.-shiftKey event)))
               (editor-key-enter props editor event))
      :up    (editor-key-up props editor event)
      :down  (editor-key-down props editor event)
      nil)))


(defn editor [props value]
  (let [on-change (partial editor-update props)
        on-key    (partial editor-key props)]
    (fn []
      [codemirror
       {:editable?   true
        :numbers?    false
        :focus?      true
        :on-change   on-change
        :on-key-down on-key}
       value])))

(defn code [text]
  [codemirror {:read-only? true :mode "clojure"} text])

(defn text [text]
  [codemirror {:read-only? true :mode "text"} text])
