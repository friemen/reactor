(ns reactor.swing-graphics
  "Rendering of graphical elements using Swing"
  (:require [reactor.graphics :refer :all :as g]
            [reactor.core :as r]
            [reactor.execution :as x])
  (:import [javax.swing JFrame JPanel]
           [java.awt.event MouseListener MouseMotionListener]))




(defmulti draw
  (fn [f g] (:type f))
  :hierarchy #'hier)

(defmethod draw ::g/circle
  [f g]
  (let [{x :x y :y} f
        d (* 2 (:radius f))]
    (.drawOval g x y d d)))

(defmethod draw ::g/rect
  [f g]
  (let [{x :x y :y w :width h :height} f]
    (.drawRect g x y w h)))



;; TODO add event listener to remove all signals on closing of the frame
(defn frame [title content-pane]
  (let [f (JFrame. title)]
    (doto f
      (.setVisible true)
      (.setDefaultCloseOperation JFrame/DISPOSE_ON_CLOSE)
      (.setSize 300 300)
      (.setContentPane content-pane))
   f))

(defn panel [shapes]
  (proxy [JPanel] []
      (paint [g] (doseq [s (r/getv shapes)] (draw s g)))))


(defn- raise-mouseevent!
  [evtsource trigger me]
  (r/raise-event! evtsource
                  {:trigger trigger
                   :x (.getX me)
                   :y (.getY me)
                   :left (= 1 (.getButton me))
                   :right (= 3 (.getButton me))}))


(defn mouse-eventsource
  [component]
  (let [e (r/eventsource)]
    (.addMouseListener component
                       (reify MouseListener
                         (mouseClicked [_ me] (raise-mouseevent! e :clicked me))
                         (mousePressed [_ me] (raise-mouseevent! e :pressed me))
                         (mouseReleased [_ me] (raise-mouseevent! e :released me))
                         (mouseEntered [_ me] (raise-mouseevent! e :entered me))
                         (mouseExited [_ me] (raise-mouseevent! e :exited me))))
    (.addMouseMotionListener component
                             (reify MouseMotionListener
                               (mouseDragged [_ me] (raise-mouseevent! e :dragged me))
                               (mouseMoved [_ me] (raise-mouseevent! e :moved me))))
    e))


(defn start!
  [f]
  (r/reset-engine!)
  (r/reset-reactives!)
  (f)
  (r/start-engine! 100)
  (str "Number of reactives: " (-> (r/pr-reactives) :active count)))


(defn- trigger? [t me]
  (= t (:trigger me)))

(def button-clicked? (partial trigger? :clicked))


(defn stage
  [title]
  (let [scene (r/signal [])
        p (panel scene)
        f (frame title p)
        mouse-events (mouse-eventsource p)
        refresh-fn (fn refresh [_] (.repaint f))]
    (->> scene (r/pass x/ui-thread) (r/process-with refresh-fn))
    (->> r/exceptions (r/react-with #(println (:event %))))
    {:scene scene
     :mouse-events mouse-events
     :frame f
     :panel p}))


