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
        y (r/lift (+ <S> (* direction speed r/etime)))]
    (-> s :scene (r/follows (r/lift (vector (move (circle 30) x y)
                                            (move (circle 10) x 100)))))
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
                      (r/hold [150 100]))]
    (-> s :scene (r/follows
                  (r/lift (create-shapes r/time clickpos))))
    s))

;; ------------------------------------------------------------------------
;; Sample 3


(defn sample3
  []
  (let [s (stage "FRP Sample 3")
        startpos (location 150 100)
        clickposE (->> s :mouse-events
                       (r/filter button-clicked?)
                       (r/map #((juxt :x :y) %)))
        clickpos (->> clickposE (r/hold startpos))
        sa (r/signal (speed-angle 0 0))
        circlepos (r/signal startpos)
        circlepos-on-click (->> clickposE
                                (r/snapshot circlepos)
                                (r/hold startpos))
        shapes (r/signal [])]
    (-> sa (r/follows
            (r/lift startpos (speed-angle 5 (angle
                                             circlepos-on-click
                                             clickpos)))))
    (-> circlepos (r/follows
                   (r/lift startpos (loc+ circlepos (motion sa)))))
    (-> s :scene (r/follows
                  (r/lift [] (vector (move (circle 10) circlepos)))))
    s))
