(ns cljs-webrepl.editor
  (:require
   [reagent.core :as r :refer [atom]]
   [clojure.string :as str]
   [cljsjs.codemirror]
   [cljsjs.codemirror.mode.clojure]
   [cljsjs.codemirror.keymap.emacs]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defn cm-options [options]
  (js-obj "readOnly" (:read-only? options false)
          "height" (name (:height options :auto))
          "autofocus" (:focus? options false)
          "lineWrapping" (:line-wrap? options false)
          "lineNumbers" (:line-numbers?  options false)
          "mode" (name (:mode options :clojure))
          "indentUnit" (:indent options 2)
          "electricChars" (:electric-chars? options true)
          "viewportMargin" (:viewport-margin options js/Infinity)
          "extraKeys" (-> (:extra-keys options nil)
                          (clj->js)
                          (js/CodeMirror.normalizeKeyMap))))

(defn cm-did-mount [node editor {:keys [on-change on-key-down] :as options} text]
  (let [element (-> node (r/dom-node))
        cm-opts (cm-options options)
        editor  (reset! editor (js/CodeMirror.fromTextArea element cm-opts))]
    (.setValue editor text)
    (when on-change
      (.on editor "change" on-change))
    (when on-key-down
      (.on editor "keydown" on-key-down))))

(defn cm-will-update [node editor [_ _ text]]
  (debugf "Update")
  (when-let [editor @editor]
    (.setValue editor text)
    (.refresh editor)))

(defn cm-will-unmount [node editor]
  (when-let [editor @editor]
    true)
  (reset! editor nil))

(defn cm-render [_ text]
  [:textarea {:value text :read-only true}])

(defn codemirror [props text]
  (let [editor (atom nil)]
    (r/create-class
     {:display-name           "codemirror"
      :component-did-mount    #(cm-did-mount %1 editor props text)
      :component-will-update  #(cm-will-update %1 editor %2)
      :component-will-unmount #(cm-will-unmount %1 editor)
      :reagent-render         cm-render})))

(defn editor-update [{:keys [state] :as props} editor change]
  (swap! state assoc :input (.getValue editor)))

(defn multi-line? [editor]
  (-> (.getValue editor)
      (str/includes? "\n")))

(defn make-submit [{:keys [submit] :as props}]
  (fn [cm]
    (submit (.getValue cm))))

(defn make-history-prev [{:keys [history-prev state] :as props}]
  (fn [cm]
    (.setValue cm (:input (history-prev)))
    (.refresh cm)))

(defn make-history-next [{:keys [history-next state] :as props}]
  (fn [cm]
    (.setValue cm (:input (history-next)))
    (.refresh cm)))

(defn wrap-ignore-multi [f]
  (fn [cm]
    (if (multi-line? cm)
      js/CodeMirror.Pass
      (f cm))))

(defn editor [props value]
  (let [history-next (make-history-next props)
        history-prev (make-history-prev props)
        submit       (make-submit props)
        extra-keys   {:Up         (-> history-prev wrap-ignore-multi)
                      :Down       (-> history-next wrap-ignore-multi)
                      :Ctrl-Up    history-prev
                      :Ctrl-Down  history-next
                      :Enter      (-> submit wrap-ignore-multi)
                      :Ctrl-Enter submit}]
    (fn []
      [codemirror
       {:editable?  true
        :numbers?   false
        :focus?     true
        :extra-keys extra-keys}
       value])))

(defn code [text]
  [codemirror {:read-only? true :mode "clojure"} text])

(defn text [text]
  [codemirror {:read-only? true :mode "text"} text])
