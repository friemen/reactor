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
(defn move
  ([form x y]
     (assoc form :x x :y y))
  ([form [x y]]
     (move form x y)))


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

;; ---------------------------------------------------------------------
;; representation of locations

(defn discrete
  "Rounds n half-up and returns an int."
  [n]
  (int (Math/round n)))


(defn motion
  "Returns a discretized motion vector [dx dy] from a speed-angle map."
  [{s :speed a :angle}]
  (let [d (* Math/PI (/ a 180))]
    (vector (-> (Math/cos d) (* s) discrete)
            (-> (Math/sin d) (* s) discrete))))


(defn- loc-op
  [f loc1 loc2]
  (vector (f (first loc2) (first loc1))
          (f (second loc2) (second loc1))))

(def loc- (partial loc-op -))
(def loc+ (partial loc-op +))


(defn speed-angle
  "If used with two arguments returns a map of the given speed and angle values.
   If used with one argument returns a 360 based angle from a diff of two locations."
  ([speed angle]
     {:speed speed :angle angle})
  ([ld]
     (let [[dx dy] ld
           h (Math/sqrt (+ (* dx dx) (* dy dy)))
           half (if (> h 0)
                  (discrete (* 180 (/ (Math/acos (/ dx h)) Math/PI)))
                  0)]
       {:angle (if (< dy 0)
                 (- 360 half)
                 half)
        :speed h})))

(defn angle
  [loc1 loc2]
  (-> (loc- loc1 loc2) speed-angle :angle))

(defn distance
  "Returns the distance of a location diff or two locations."
  ([ld]
     (-> ld speed-angle :speed))
  ([loc1 loc2]
     (-> (loc- loc1 loc2) distance)))

(defn location
  "Returns a vector [x y] that represents a 2D location."
  [x y]
  (vector x y))



