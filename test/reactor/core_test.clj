(ns reactor.core-test
  (:require [reactor.core :as r])
  (:use clojure.test))

(deftest lift-test
  (let [n1 (r/signal 0)
        n2 (r/signal 0)
        n1half (r/lift / n1 2) ; always contains the half of n1's value 
        sum (r/lift + n1 n2) ; always contains the sum of n1 and n2
        sum>10 (->> sum
                    (r/trigger #(when (> % 10) "ALARM!"))
                    (r/react-with #(println %)))]
    (r/setv! n1 4)
    (is (= 4 (r/getv sum))) ; check that sum is up-to-date
    (is (= 2 (r/getv n1half))) ; check that n1half is up-to-date
    (r/setv! n2 8)
    (is (= 12 (r/getv sum))))) ; check that sum is up-to-date


(deftest trigger-test
  (let [n (r/signal 0)
        alarm-events (->> n (r/trigger #(when (> % 10) "ALARM!")))
        alarm-signal (->> alarm-events r/as-signal)]
    (r/setv! n 9)
    (is (= nil (r/getv alarm-signal))) ; not ALARM must be set
    (r/setv! n 11)
    (is (= "ALARM!" (r/getv alarm-signal))))) ; check that ALARM is set


(deftest filter-test
  (let [e1 (r/eventsource)
        e2 (->> e1 (r/filter #(not= "Foo" %)))
        sig (->> e2 r/as-signal)]
    (is (nil? (r/getv sig)))
    (r/raise-event! e1 "Foo")
    (is (nil? (r/getv sig))) ; ensure that Foo was filtered out
    (r/raise-event! e1 "Bar")
    (is (= "Bar" (r/getv sig))))) ; check that Bar passed


(deftest switch-test
  (let [e1 (r/eventsource)
        sig1 (r/signal 0)
        sig2 (->> e1 (r/switch sig1))
        sig3 (r/signal 10)]
    (r/setv! sig1 42)
    (is (= 42 (r/getv sig2))) ; ensure that sig2 follows sig1
    (r/raise-event! e1 sig3) ; emit sig3 as event
    (is (= 10 (r/getv sig2))) ; now sig2 show reflect sig3
    (r/setv! sig1 4711) ; change sig1
    (is (= 10 (r/getv sig2))) ; sig1 change must not get propagated to sig2
    (r/setv! sig3 13) ; change sig3
    (is (= 13 (r/getv sig2))))) ; make sure sig2 follows sig3


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

(deftest reduce-test
  (let [initial-state {:state :idle, :path []}
        mouse-events (r/eventsource)
        drawing-state (->> mouse-events (r/reduce draw-statemachine initial-state))]
    (r/raise-event! mouse-events (mouse-action :left-press [1 2]))
    (is (= {:state :drawing
            :path [[1 2]]}
           (r/getv drawing-state)))
    (r/raise-event! mouse-events (mouse-action :move [3 4]))
    (r/raise-event! mouse-events (mouse-action :left-release [5 6]))
    (is (= {:state :idle
            :path [[1 2] [3 4] [5 6]]}
           (r/getv drawing-state)))))