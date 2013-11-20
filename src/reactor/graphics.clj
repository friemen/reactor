(ns reactor.graphics
  "Combinators to specify graphical elements")

;; Concepts

;; An element takes space in a layout.

;; A shape is a closed geometric object without position or orientation.

;; A form is ready to be rendered, it has a position + orientation.



;; elements
(defn text [s]
  {:type ::text :text s})

(defn image [resource])

(defn box [width height])

(defn collage [width height & shapes])

;; layout of elements
(defn flow [direction & elements])


;; decorators
(defn width [element width]
  (assoc element :width width))

(defn height [element height]
  (assoc element :height height))

(defn size [element width height]
  (assoc element :width width :height height))

(defn font [text-element])
(defn bold [text-element])
(defn italic [text-element])
(defn color [element color])

(defn container [width height position element])

;; shapes
(defn rect [width height]
  {:type ::rect :width width :height height})

(defn square [side]
  {:type ::square :width side :height side})

(defn circle [radius]
  {:type ::circle :radius radius :width (* radius 2) :height (* radius 2)})

;; create forms
(defn to-form [element])
(defn filled [shape color])
(defn outlined [shape color])

;; transform forms
(defn move [form x y]
  (assoc form :x x :y y))

(defn rotate [form degrees])
(defn scale [form factor])
(defn group [forms])


(def hier (-> (make-hierarchy)
              (derive ::shape ::element)
              (derive ::text ::element)
              (derive ::image ::element)
              (derive ::box ::element)
              (derive ::collage ::element)
              (derive ::rect ::shape)
              (derive ::square ::shape)
              (derive ::circle ::shape)))



