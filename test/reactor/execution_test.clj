(ns reactor.execution-test
  (:require [reactor.execution :as x])
  (:use [clojure.test]))

(def c (atom 0))

(defn tick []
  (swap! c inc))

(defn wait [n]
  (Thread/sleep n))

(deftest schedule-timer-test
  (let [exec (x/timer-executor 50)]
    (reset! c 0)
    (x/schedule exec tick)
    (wait 200)
    (x/shutdown exec)
    (is (< 3 @c))))


(deftest shutdown-timer-test
  (let [exec (x/timer-executor 50)]
    (reset! c 0)
    (x/schedule exec tick)
    (wait 200)
    (x/shutdown exec)
    (wait 200)
    (is (> 6 @c))))


(deftest executor-test
  (let [exec (x/executor)
        t (atom nil)]
    (x/schedule exec #(reset! t (Thread/currentThread)))
    (wait 50)
    (is (not= nil t)) ; actually executed
    (is (not= (Thread/currentThread) t)))) ; but not in same thread
