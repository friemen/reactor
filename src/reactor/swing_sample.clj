(ns reactor.swing-sample
  (:require [reactor.core :as r]
            [reactor.execution :as x])
  (:import [javax.swing JFrame JPanel]
           [java.awt.event MouseListener MouseMotionListener]))

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


;; Swing based infrastructure for displaying shapes and connecting to mouse

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


(defn raise-mouseevent! [evtsource trigger me]
  (r/raise-event! evtsource
                  {:trigger trigger
                   :x (.getX me)
                   :y (.getY me)
                   :left (= 1 (.getButton me))
                   :right (= 3 (.getButton me))}))


(defn connect-mouse! [component e]
  (.addMouseListener component (reify MouseListener
                                 (mouseClicked [_ me] (raise-mouseevent! e :clicked me))
                                 (mousePressed [_ me] (raise-mouseevent! e :pressed me))
                                 (mouseReleased [_ me] (raise-mouseevent! e :released me))
                                 (mouseEntered [_ me] (raise-mouseevent! e :entered me))
                                 (mouseExited [_ me] (raise-mouseevent! e :exited me))))
  (.addMouseMotionListener component (reify MouseMotionListener
                                       (mouseDragged [_ me] (raise-mouseevent! e :dragged me))
                                       (mouseMoved [_ me] (raise-mouseevent! e :moved me))))
  e)


;; a FRP animation program

(defn- moving-circle [t [mx my]]
  (let [x (+ 100 (* 100 (Math/sin t)))]
    (Circle. x 150 20)))

(defn- pulsing-rectangle [t [mx my]]
  (let [asin (-> t (Math/sin) (Math/abs))
        w (* asin 100)
        h (* asin 50)
        x (- mx (/ w 2))
        y (- my (/ h 2))]
    (Rectangle. x y w h)))

(defn- create-shapes [t mpos]
  [(moving-circle t mpos)
   (pulsing-rectangle t mpos)])

(defn start-animation []
  (let [time (r/time 50)
        mouseevents (r/eventsource)
        clickpos (->> mouseevents
                      (r/filter #(= (:trigger %) :clicked))
                      (r/map #((juxt :x :y) %))
                      (r/hold [150 100]))
        shapes (r/lift create-shapes time clickpos)
        panel (shape-panel shapes)
        f (frame "Hello FRP World" panel)]
    (connect-mouse! panel mouseevents)
    (->> shapes
         (r/pass x/ui-thread)
         (r/process-with (fn [_] (.repaint f))))))
