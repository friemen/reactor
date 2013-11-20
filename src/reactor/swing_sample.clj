(ns reactor.swing-sample
  "Some Swing based graphics samples"
  (:require [reactor.core :as r]
            [reactor.execution :as x]
            [reactor.graphics :refer :all :as g]
            [reactor.swing-graphics :refer :all])
  (:import [javax.swing JFrame JPanel]
           [java.awt.event MouseListener MouseMotionListener]))


;; ------------------------------------------------------------------------
;; Sample 1

(defn sample1
  []
  (let [s (stage "FRP Sample 1")
        direction (->> s :mouse-events
                       (r/filter button-clicked?)
                       (r/reduce (fn [d evt] (* d -1)) 1))
        speed (r/signal 10)
        x (r/signal 100)
        y (r/lift (+ <S> (* direction speed r/etime)))
        shapes (r/lift (vector (move (circle 30) x y)
                               (move (circle 10) x 100)))]
    (r/bind! concat [shapes] (:scene s))
    s))



;; ------------------------------------------------------------------------
;; Sample 2

(defn- moving-circle [t [mx my]]
  (let [x (+ 100 (* 100 (Math/sin t)))]
    (-> (circle 20) (move x 150))))

(defn- pulsing-rectangle [t [mx my]]
  (let [asin (-> t (Math/sin) (Math/abs))
        w (* asin 100)
        h (* asin 50)
        x (- mx (/ w 2))
        y (- my (/ h 2))]
    (-> (rect w h) (move x y))))

(defn- create-shapes [t mpos]
  [(moving-circle t mpos)
   (pulsing-rectangle t mpos)])

(defn sample2 []
  (let [s (stage "FRP Sample 2")
        clickpos (->> s :mouse-events
                      (r/filter button-clicked?)
                      (r/map #((juxt :x :y) %))
                      (r/hold [150 100]))
        shapes (r/lift (create-shapes r/time clickpos))]
    (r/bind! concat [shapes] (:scene s))
    s))

