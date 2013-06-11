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

(defn shape-panel [shapes]
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

(defn pulsing-rectangle [t]
  (let [asin (-> t (Math/sin) (Math/abs))
        w (* asin 100)
        h (* asin 50)
        x (- 150 (/ w 2))
        y (- 100 (/ h 2))]
    (Rectangle. x y w h)))

(defn create-shapes [t]
  [(moving-circle t)
   (pulsing-rectangle t)])


(def shapes (->> (r/time 50)
                 (r/lift create-shapes)))

(def f (frame "Hello FRP World" (shape-panel shapes)))

(->> shapes (r/process-with (fn [_] (.repaint f))))







