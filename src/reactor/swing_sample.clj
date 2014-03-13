(ns reactor.swing-sample
  "Some Swing based graphics samples"
  (:require [reactor.core :as r]
            [reactor.execution :as x]
            [reactor.graphics :refer :all :as g]
            [reactor.swing-graphics :refer :all])
  (:import [javax.swing JFrame JPanel]
           [java.awt.event MouseListener MouseMotionListener]))


; in the REPL try
;   (start! sample1)
;   (start! sample2)
;   (start! sample3)


;; ------------------------------------------------------------------------
;; Sample 1

(defn sample1
  []
  (let [s (stage "FRP Sample 1")
        direction (->> s :mouse-events
                       (r/filter button-clicked?)
                       (r/reduce (fn [d evt] (* d -1)) 1))
        speed (r/signal 20)
        x (r/signal 100)
        y (r/lift (+ <S> (* direction speed r/etime)))]
    (-> s :scene (r/follows (r/lift (vector (-> (circle 30) (move x y))
                                            (-> (circle 10) (move x 100))))))
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
        clickposE (r/eventsource)
        clickpos (r/signal startpos)
        sa (r/signal (speed-angle 0 0))
        circlepos (r/signal startpos)
        shapes (r/signal [])]
    (-> clickposE (r/follows
                   (->> s :mouse-events
                        (r/filter button-clicked?)
                        (r/map #((juxt :x :y) %)))))
    (-> sa (r/follows
            (r/lift startpos (speed-angle 5 (angle
                                             (->> clickposE
                                                  (r/snapshot circlepos)
                                                  (r/hold startpos))
                                             (->> clickposE (r/hold startpos)))))))
    (-> circlepos (r/follows
                   (r/lift startpos (loc+ circlepos (motion sa)))))
    (-> s :scene (r/follows
                  (r/lift [] (-> (circle 10) (move circlepos) vector))))
    s))
