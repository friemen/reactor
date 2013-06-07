(ns reactor.swing-sample
  (:require [reactor.core :as r])
  (:import [javax.swing JFrame JPanel]))

;; define shapes

(defprotocol Shape
  (draw [s g]))

(defrecord Circle [x y radius])
(defrecord Rectangle [x y width height])

;; attach Shape function implementations

(extend-protocol Shape
  Circle
  (draw [s g]
    (let [{x :x y :y} s
          d (* 2 (:radius s))]
      (.drawOval g x y d d)))
  Rectangle
  (draw [s g]
    (let [{x :x y :y w :width h :height} s]
      (.drawRect g x y w h))))


;; Swing based infrastructure for displaying shapes

(defn shape-panel []
  (proxy [JPanel] []
      (paint [g] (doseq [s (r/getv shapes)] (draw s g)))))

(defn frame [title content-pane]
  (let [f (JFrame. title)]
    (doto f
      (.setVisible true)
      (.setDefaultCloseOperation JFrame/DISPOSE_ON_CLOSE)
      (.setSize 300 300)
      (.setContentPane content-pane))
   f))


;; a FRP animation program

(defn moving-circle [t]
  (let [x (+ 100 (* 100 (Math/sin t)))]
    (Circle. x 150 20)))

(defn create-shapes [t]
  [(moving-circle t)])


(def f (frame "Hello FRP World" (shape-panel)))

(def shapes (->> (r/time 50)
                 (r/lift create-shapes)
                 (r/process-with (fn [_] (.repaint f)))))


