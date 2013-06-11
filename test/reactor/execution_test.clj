(ns reactor.execution-test
  (:require [reactor.execution :as x])
  (:use [clojure.test]))

(def c (atom 0))

(defn tick []
  (swap! c inc))

(deftest schedule-timer-test
  (let [exec (x/timer-executor 50)]
    (reset! c 0)
    (x/schedule exec tick)
    (x/wait 200)
    (x/cancel exec)
    (is (< 3 @c))))


(deftest shutdown-timer-test
  (let [exec (x/timer-executor 50)]
    (reset! c 0)
    (x/schedule exec tick)
    (x/wait 200)
    (x/cancel exec)
    (x/wait 200)
    (is (> 6 @c))))


(deftest executor-test
  (let [exec (x/executor)
        t (atom nil)]
    (x/schedule exec #(reset! t (Thread/currentThread)))
    (x/wait 50)
    (is (not= nil t)) ; actually executed
    (is (not= (Thread/currentThread) t)))) ; but not in same thread


(deftest delay-test
  (let [exec (x/delayed-executor 100)
        c (atom 0)]
    (x/schedule exec #(swap! c inc))
    (x/wait 50)
    (is (= 0 @c))
    (x/wait 100)
    (is (= 1 @c))))


(deftest calm-test
  (let [exec (x/calmed-executor 100)
        c (atom 0)
        f #(swap! c inc)]
    (x/schedule exec f) (x/wait 10)
    (x/schedule exec f) (x/wait 10)
    (x/schedule exec f) (x/wait 10)
    (is (= 0 @c))
    (x/wait 100)
    (is (= 1 @c))))
