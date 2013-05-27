(ns reactor.core-test
  (:use clojure.test
        reactor.core))

(deftest bind-test
  (let [n1 (make-signal 0)
          n2 (make-signal 0)
          sum (-> (make-signal 0) (bind + n1 n2))
          sum>10 (-> sum
                     (trigger #(when (> % 10) "ALARM!"))
                     (react-with #(println %)))]
      (set-value! n1 3)
      (is (= 3 (get-value sum)))
      (set-value! n2 9)
      (is (= 12 (get-value sum)))))


(deftest trigger-test
  (let [n (make-signal 0)
        alarm-events (-> n (trigger #(when (> % 10) "ALARM!")))
        alarm-signal (-> alarm-events switch)]
    (set-value! n 9)
    (is (= nil (get-value alarm-signal)))
    (set-value! n 11)
    (is (= "ALARM!" (get-value alarm-signal)))))


;; naive state machine implementation

(defn- illegalstate
  [s evt]
  (throw (IllegalStateException. (str "Action " (:action evt) " is not expected in state " (:state s)))))

(defmulti draw-statemachine
  (fn [s evt] (:state s))
  :default :idle)

(defmethod draw-statemachine :idle
  [s evt]
  (case (:action evt)
    :left-press {:state :drawing
                 :path [(:pos evt)]}
    :move s
    (illegalstate s evt)))

(defmethod draw-statemachine :drawing
  [s evt]
  (let [newpath (conj (:path s) (:pos evt))]
    (case (:action evt)
      :left-release (do (println "Drawing" newpath)
                        {:state :idle
                         :path newpath})
      :move {:state :drawing
             :path newpath}
      (illegalstate s evt))))

(defn mouse-action [action position] {:action action, :pos position})

;; test demonstrating how a statemachine can be used in conjunction with event sources

(deftest switch-with-test
  (let [initial-state {:state :idle, :path []}
        mouse-events (make-eventsource)
        drawing-state (-> mouse-events (switch-with draw-statemachine initial-state))]
    (raise-event! mouse-events (mouse-action :left-press [1 2]))
    (is (= {:state :drawing
            :path [[1 2]]}
           (get-value drawing-state)))
    (raise-event! mouse-events (mouse-action :move [3 4]))
    (raise-event! mouse-events (mouse-action :left-release [5 6]))
    (is (= {:state :idle
            :path [[1 2] [3 4] [5 6]]}
           (get-value drawing-state)))))
