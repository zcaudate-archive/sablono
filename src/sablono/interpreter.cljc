(ns sablono.interpreter
  (:require [clojure.string :refer [blank? join]]
            [sablono.normalize :as normalize]
            [sablono.util :as util]
            #?(:cljs [goog.object :as object])))

(defprotocol IInterpreter
  (interpret [this] "Interpret a Clojure data structure as a React fn call."))

#?(:cljs (defn update-state
           "Updates the state of the wrapped input element."
           [component next-props property value]
           (let [on-change (object/getValueByKeys component "state" "onChange")
                 next-state #js {}]
             (object/extend next-state next-props #js {:onChange on-change})
             (object/set next-state property value)
             (.setState component next-state))))

;; A hack to force input elements to always update itself immediately,
;; without waiting for requestAnimationFrame.

#?(:cljs
   (defn wrap-form-element [element property]
     (let [ctor (fn [props]
                  (this-as this
                    (set! (.-state this)
                          (let [state #js {}]
                            (->> #js {:onChange (goog.bind (object/get this "onChange") this)}
                                 (object/extend state props))
                            state))
                    (.call js/React.Component this props)))]
       (set! (.-displayName ctor) (str "wrapped-" element))
       (goog.inherits ctor js/React.Component)
       (specify! (.-prototype ctor)
         Object
         (onChange [this event]
           (when-let [handler (.-onChange (.-props this))]
             (handler event)
             (update-state
              this (.-props this) property
              (object/getValueByKeys event "target" property))))

         (componentWillReceiveProps [this new-props]
           (let [state-value (object/getValueByKeys this "state" property)
                 element-value (object/get (js/ReactDOM.findDOMNode this) property)]
             ;; On IE, onChange event might come after actual value of
             ;; an element have changed. We detect this and render
             ;; element as-is, hoping that next onChange will
             ;; eventually come and bring our modifications anyways.
             ;; Ignoring this causes skipped letters in controlled
             ;; components
             ;; https://github.com/facebook/react/issues/7027
             ;; https://github.com/reagent-project/reagent/issues/253
             ;; https://github.com/tonsky/rum/issues/86
             ;; TODO: Find a better solution, since this conflicts
             ;; with controlled/uncontrolled inputs.
             ;; https://github.com/r0man/sablono/issues/148
             (if (not= state-value element-value)
               (update-state this new-props property element-value)
               (update-state this new-props property (object/get new-props property)))))

         (render [this]
           (js/React.createElement element (.-state this))))
       ctor)))

#?(:cljs (def wrapped-input))
#?(:cljs (def wrapped-checked))
#?(:cljs (def wrapped-select))
#?(:cljs (def wrapped-textarea))

#?(:cljs (defn lazy-load-wrappers []
           (when-not wrapped-textarea
             (set! wrapped-input (wrap-form-element "input" "value"))
             (set! wrapped-checked (wrap-form-element "input" "checked"))
             (set! wrapped-select (wrap-form-element "select" "value"))
             (set! wrapped-textarea (wrap-form-element "textarea" "value")))))

(defn ^boolean controlled-input?
  "Returns true if `type` and `props` are used a controlled input,
  otherwise false."
  [type props]
  #?(:cljs (and (object? props)
                (case type
                  "input"
                  (or (exists? (.-checked props))
                      (exists? (.-value props)))
                  "select"
                  (exists? (.-value props))
                  "textarea"
                  (exists? (.-value props))
                  false))))

#?(:cljs
   (defn element-class
     "Returns either `type` or a wrapped element for controlled
     inputs."
     [type props]
     (if (controlled-input? type props)
       (do (lazy-load-wrappers)
           (case type
             "input"
             (case (and (object? props) (.-type props))
               "radio" wrapped-checked
               "checkbox" wrapped-checked
               wrapped-input)
             "select" wrapped-select
             "textarea" wrapped-textarea
             type))
       type)))

#?(:cljs
   (defn create-element [type props & children]
     (let [class (element-class type props)
           children (remove nil? children)]
       (if (empty? children)
         (js/React.createElement class props)
         (apply js/React.createElement class props children)))))

#?(:cljs
   (defn attributes [attrs]
     (when-let [js-attrs (clj->js (util/html-to-dom-attrs attrs))]
       (let [class (.-className js-attrs)
             class (if (array? class) (join " " class) class)]
         (if (blank? class)
           (js-delete js-attrs "className")
           (set! (.-className js-attrs) class))
         js-attrs))))

(defn- interpret-seq
  "Interpret the seq `x` as HTML elements."
  [x]
  ;; Forces the seq x to be realized to avoid a problem caused by a combination
  ;; of lazy seq and binding (e.g. implementation of om.core/build-all).
  ;; https://github.com/r0man/sablono/issues/147
  (into [] (map interpret) x))

#?(:cljs
   (defn element
     "Render an element vector as a HTML element."
     [element]
     (let [[type attrs content] (normalize/element element)]
       (apply create-element type
              (attributes attrs)
              (interpret-seq content)))))

#?(:cljs
   (defn- interpret-vec
     "Interpret the vector `x` as an HTML element or a the children of
  an element."
     [x]
     (if (util/element? x)
       (element x)
       (interpret-seq x))))

#?(:cljs
   (extend-protocol IInterpreter
     Cons
     (interpret [this]
       (interpret-seq this))
     ChunkedCons
     (interpret [this]
       (interpret-seq this))
     ChunkedSeq
     (interpret [this]
       (interpret-seq this))
     LazySeq
     (interpret [this]
       (interpret-seq this))
     List
     (interpret [this]
       (interpret-seq this))
     IndexedSeq
     (interpret [this]
       (interpret-seq this))
     Subvec
     (interpret [this]
       (interpret-vec this))
     PersistentVector
     (interpret [this]
       (interpret-vec this))
     default
     (interpret [this]
       this)
     nil
     (interpret [this]
       nil)))
