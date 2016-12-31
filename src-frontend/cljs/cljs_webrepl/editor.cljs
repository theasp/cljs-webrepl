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
      (.on editor "change" on-change))))

(defn cm-will-update [node editor [_ _ text]]
  (debugf "Update")
  (when-let [editor @editor]
    (.setValue editor text)
    (.refresh editor)))

(defn cm-will-unmount [node editor]
  (when-let [editor @editor]
    true)
  (reset! editor nil))

(defn cm-render [props text]
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

(defn wrap-on-change [{:keys [on-change]}]
  (when on-change
    (fn [cm]
      (on-change (.getValue cm)))))

(defn wrap-on-submit [{:keys [on-submit] :as props}]
  (fn [cm]
    (on-submit (.getValue cm))))

(defn wrap-history-prev [{:keys [history-prev state] :as props}]
  (fn [cm]
    (.setValue cm (:input (history-prev)))
    (.refresh cm)))

(defn wrap-history-next [{:keys [history-next state] :as props}]
  (fn [cm]
    (.setValue cm (:input (history-next)))
    (.refresh cm)))

(defn wrap-ignore-multi [f]
  (fn [cm]
    (if (multi-line? cm)
      js/CodeMirror.Pass
      (f cm))))

(defn insert-pair [cm pair]
  (doto cm
    (.replaceSelection pair)
    (.execCommand "goCharLeft")))

(defn editor [props text]
  (let [on-change    (wrap-on-change props)
        history-next (wrap-history-next props)
        history-prev (wrap-history-prev props)
        on-submit    (wrap-on-submit props)
        extra-keys   {:Up         (-> history-prev wrap-ignore-multi)
                      :Down       (-> history-next wrap-ignore-multi)
                      :Ctrl-Up    history-prev
                      :Ctrl-Down  history-next
                      :Enter      (-> on-submit wrap-ignore-multi)
                      :Ctrl-Enter on-submit
                      :Shift-9    #(insert-pair % "()")
                      "["         #(insert-pair % "[]")
                      "Shift-{"   #(insert-pair % "{}")
                      "Shift-'"   #(insert-pair % "\"\"")}]
    (fn []
      [codemirror (-> {:editable?  true
                       :numbers?   false
                       :focus?     true
                       :extra-keys extra-keys
                       :on-change  on-change}
                      (merge (dissoc props :on-change)))
       text])))

(defn code [text]
  [codemirror {:read-only? true :mode "clojure"} text])

(defn text [text]
  [codemirror {:read-only? true :mode "text"} text])
