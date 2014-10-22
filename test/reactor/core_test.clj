(ns reactor.core-test
  (:require [clojure.test :refer :all]
            [reactor.core :as r]
            [reactnet.netrefs :as refs]
            [reactnet.scheduler :as sched]
            [reactnet.core :as rn :refer [push! complete! pp]]
            [reactnet.debug :as dbg]))


#_(do (dbg/on) (dbg/clear)
      (with-clean-network -test)
      (dbg/to-console (dbg/lines)))

;; ---------------------------------------------------------------------------
;; Support functions

(defn with-clean-network
  [f]
  (rn/with-netref (refs/agent-netref (rn/make-network "unittests" []))
    (f)
    (-> rn/*netref* rn/scheduler sched/cancel-all)))

(use-fixtures :each with-clean-network)

(defn wait
  ([]
     (wait 200))
  ([millis]
     (Thread/sleep millis)))

(defn push-and-wait!
  [& rvs]
  (doseq [[r v] (partition 2 rvs)]
    (assert (rn/reactive? r))
    (push! r v))
  (wait))


;; ---------------------------------------------------------------------------
;; Tests of special reactive factories


(deftest fnbehavior-seqstream-test
  (let [r       (atom [])
        seconds (r/fnbehavior #(long (/ (System/currentTimeMillis) 1000)))
        numbers (r/seqstream (range 10))
        c       (->> (r/map vector seconds numbers) (r/swap! r conj))]
    (wait)
    (is (= (range 10) (->> r deref (map second))))))


(deftest eval-test
  (testing "A function invocation"
    (let [r  (atom [])
          e  (->> (constantly :foo) (r/eval) (r/swap! r conj))]
      (wait)
      (is (= [:foo] @r))))
  (testing "A future"
    (let [r  (atom [])
          e  (->> (r/in-future (fn [] 
                                 (Thread/sleep 300)
                                 :foo))
                  (r/eval)
                  (r/swap! r conj))]
      (wait)
      (is (= [] @r))
      (wait)
      (is (= [:foo] @r))))
  (testing "An IDeref instance"
    (let [i (atom "noob")
          r (atom [])
          e (->> (r/eval i)
                 (r/swap! r conj))]
      (wait)
      (is (= ["noob"] @r))
      (is (rn/completed? e)))))


(deftest just-test
  (testing "Just a value"
    (let [r  (atom [])
          j  (r/just 42)]
      (wait)
      (is (rn/pending? j))
      (r/swap! r conj j)
      (wait)
      (is (= [42] @r))
      (is (rn/completed? j)))))


(deftest sample-test
  (testing "Sample constant value"
    (let [r   (atom [])
          s   (->> :foo (r/sample 100) (r/swap! r conj))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [:foo :foo :foo :foo] (take 4 @r)))))
  (testing "Sample by invoking a function"
    (let [r   (atom [])
          s   (->> #(count @r) (r/sample 100) (r/swap! r conj))]
      (wait 500)
      (is (<= 4 (count @r)))
      (is (= [0 1 2 3] (take 4 @r)))))
  (testing "Sample from a ref"
    (let [r   (atom [])
          a   (atom 0)
          s   (->> a (r/sample 100) (r/swap! r conj))]
      (wait 150)
      (reset! a 1)
      (wait 400)
      (is (<= 4 (count @r)))
      (is (= [0 0 1 1] (take 4 @r)))))
  (testing "Cancel sample task"
    (let [r   (atom [])
          s   (->> 42 (r/sample 100) (r/swap! r conj))]
      (wait 150)
      (r/complete! s)
      (wait 200)
      (is (<= 2 (count @r))))))


(deftest timer-test
  (let [r   (atom [])
        t   (->> (r/timer 100) (r/swap! r conj))]
    (wait 500)
    (r/complete! t)
    (is (= [0 1 2 3] (take 4 @r)))
    (wait 200)
    (is (<= (count @r) 6))))


;; ---------------------------------------------------------------------------
;; Tests of combinators

(deftest amb-test
  (testing "Following a stream until completion."
    (let [r   (atom [])
          e1  (r/eventstream)
          e2  (r/eventstream)
          c   (->> (r/amb e1 e2) (r/swap! r conj))]
      (push-and-wait! e2 :foo e1 :bar e2 :baz)
      (is (= [:foo :baz] @r))
      (r/complete! e2)
      (wait)
      (is (rn/completed? c))))
  (testing "Following no stream, until all complete"
    (let [r   (atom [])
          e1  (r/eventstream)
          e2  (r/eventstream)
          c   (->> (r/amb e1 e2) (r/swap! r conj))]
      (r/complete! e1)
      (r/complete! e2)
      (wait)
      (is (= [] @r))
      (is (rn/completed? c)))))


(deftest any-test
  (testing "The first true results in true."
    (let [r   (atom [])
          e1  (r/eventstream)
          c   (->> e1 r/any (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) [false true]))
      (is (= [true] @r))
      (is (rn/completed? c))))
  (testing "False is emitted only after completion."
    (let [r   (atom [])
          e1  (r/eventstream)
          c   (->> e1 r/any (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e1) [false false]))
      (is (= [] @r))
      (complete! e1)
      (wait)
      (is (= [false] @r))
      (is (rn/completed? c)))))


(deftest buffer-test
  (testing "Buffer by number of items"
    (let [r   (atom [])
          e1  (r/eventstream)
          c   (->> e1 (r/buffer-c 2) (r/swap! r conj))]
      (push-and-wait! e1 1 e1 2)
      (is (= [[1 2]] @r))
      (apply push-and-wait! (interleave (repeat e1) [3 4 5 6 7]))
      (is (= [[1 2] [3 4] [5 6]] @r))
      (complete! e1)
      (wait)
      (is (= [[1 2] [3 4] [5 6] [7]] @r))))
  (testing "Buffer by time"
    (let [r   (atom [])
          e1  (r/eventstream)
          c   (->> e1 (r/buffer-t 500) (r/swap! r conj))]
      (push-and-wait! e1 42)
      (is (= [] @r))
      (wait 500)
      (is (= [[42]] @r))))
  (testing "Buffer by time and number of items"
    (let [r   (atom [])
          e1  (r/eventstream)
          c   (->> e1 (r/buffer 3 500) (r/swap! r conj))]
      (push-and-wait! e1 42 e1 43 e1 44 e1 45)
      (is (= [[42 43 44]] @r))
      (wait 500)
      (is (= [[42 43 44] [45]] @r))
      (push! e1 46)
      (complete! e1)
      (wait)
      (is (= [[42 43 44] [45] [46]] @r)))))


(deftest changes-test
  (let [r   (atom [])
        b   (r/behavior nil)
        c   (->> b r/changes (r/swap! r conj))]
    (push-and-wait! b 1 b 1 b 42)
    (is (= [[nil 1] [1 42]] @r))))


(deftest concat-test
  (let [r   (atom [])
        e1  (r/eventstream :label "e1")
        e2  (r/eventstream :label "e2")
        s   (r/seqstream [:foo :bar :baz])
        c   (->> e1 (r/concat s e1 e2) (r/swap! r conj))]
    (push-and-wait! e2 1 e2 2 e2 3
                e1 "FOO" e1 "BAR" e1 ::rn/completed)
    (is (= [:foo :bar :baz "FOO" "BAR" 1 2 3] @r))))


(deftest count-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e r/count (r/swap! r conj))]
    (push-and-wait! e :foo e :bar e :baz)
    (is (= [1 2 3] @r))
    (complete! e)
    (wait)
    (is (rn/completed? c))))


(deftest debounce-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/debounce 250) (r/swap! r conj))]
    (push-and-wait! e :foo e :bar e :baz)
    (is (= [] @r))
    (wait 100)
    (is (= [:baz] @r))
    (rn/push! e :foo)
    (wait 300)
    (is (= [:baz :foo] @r))))


(deftest delay-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/delay 500) (r/swap! r conj))]
    (push-and-wait! e :foo)
    (is (= [] @r))
    (wait 500)
    (is (= [:foo] @r))))


(deftest distinct-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e r/distinct (r/swap! r conj))]
    (push-and-wait! e :foo e :bar e :foo e :baz e :bar)
    (is (= [:foo :bar :baz] @r))))


(deftest drop-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/drop 2) (r/swap! r conj))]
    (push-and-wait! e :foo e :bar e :baz)
    (is (not (rn/pending? e)))
    (is (= [:baz] @r))
    (complete! e)
    (wait)
    (is (rn/completed? c))))


(deftest drop-last-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/drop-last 2) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) (range 6)))
    (is (= [0 1 2 3] @r))
    (push-and-wait! e 6 e ::rn/completed)
    (is (= [0 1 2 3 4] @r))
    (is (rn/completed? c))))


(deftest drop-while-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/drop-while (partial >= 5)) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) (range 10)))
    (is (= [6 7 8 9] @r))))


(deftest every-test
  (testing "Direct completion emits true."
    (let [r  (atom [])
          e  (r/eventstream)
          c  (->> e r/every (r/swap! r conj))]
      (complete! e)
      (wait)
      (is (= [true] @r))))
  (testing "The first False results in False."
    (let [r  (atom [])
          e  (r/eventstream)
          c  (->> e r/every (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e) [true false]))
      (is (= [false] @r))))
  (testing "True is emitted only after completion."
    (let [r  (atom [])
          e  (r/eventstream)
          c  (->> e r/every (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e) [true true]))
      (is (= [] @r))
      (complete! e)
      (wait)
      (is (= [true] @r)))))


(deftest filter-test
  (let [r        (atom [])
        values   (range 10)
        expected (filter odd? values)
        e        (r/eventstream)
        c        (->> e (r/filter odd?) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) values))
    (is (= [1 3 5 7 9] @r))
    (complete! e)
    (wait)
    (is (rn/completed? c))))


(deftest flatmap-test
  (let [r       (atom [])
        values  (range 5)
        f       (fn [x] (->> x r/just (r/map (partial * 2)) (r/map (partial + 1))))
        e       (r/eventstream)
        c       (->> e (r/flatmap f) (r/scan + 0) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) values))    
    (is (= [1 4 9 16 25] @r))
    (complete! e)
    (wait)
    (is (rn/completed? c))))


(deftest into-test
  (let [e  (r/eventstream)
        b1 (r/behavior :label "b1")
        b2 (r/behavior :label "b2")]
    (->> e (r/into b1 b2))
    (push-and-wait! e 1)
    (is (and (= @b1 1) (= @b2 1)))))


(deftest map-test
  (testing "Two eventstreams"
    (let [r   (atom [])
          e1  (r/eventstream :label "e1")
          e2  (r/eventstream :label "e2")
          c   (->> (r/map + e1 e2) (r/swap! r conj))]
      (push-and-wait! e1 1 e1 2 e2 1 e2 2)
      (is (= [2 4] @r))))
  (testing "Two behaviors"
    (let [r   (atom [])
          b1  (r/behavior 0 :label "b1")
          b2  (r/behavior 0 :label "b2")
          c   (->> (r/map + b1 b2) (r/swap! r conj))]
      (push-and-wait! b1 1 b1 2 b2 1 b2 2)
      (is (= [0 1 2 3 4] @r))))
  (testing "One eventstream, one behavior"
    (let [r   (atom [])
          e   (r/eventstream)
          b   (r/behavior 0 :label "b")
          c   (->> (r/map + e b) (r/swap! r conj))]
      (push-and-wait! b 1 b 2 e 1 e 2)
      (is (= [3 4] @r))))
  (testing "One infinite eventstream, one finite eventstream"
    (let [r   (atom [])
          e1  (r/eventstream :label "e1")
          e2  (r/seqstream (repeat 42))
          c   (->> (r/map + e1 e2) (r/swap! r conj))]
      (push-and-wait! e1 1 e1 2 e1 3)
      (is (= [43 44 45] @r))
      (complete! e1)
      (wait)
      (is (rn/completed? c)))))


(deftest mapcat-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/mapcat :items) (r/swap! r conj))]
    (push-and-wait! e {:name "Me" :items ["foo" "bar" "baz"]})
    (is (= ["foo" "bar" "baz"] @r))))


(deftest merge-test
  (testing "Merge preserves order"
    (let [r        (atom [])
          streams  (for [i (range 5)]
                     (r/eventstream :label (str "e" i)))
          expected (repeatedly 400 #(rand-int 100))
          c        (->> streams (apply r/merge) (r/swap! r conj))]
      (doseq [x expected]
        (push! (rand-nth streams) x))
      (wait 1500)
      (is (= expected @r))))
  (testing "Merge completes when last input completes"
    (let [e1 (r/eventstream :label "e1")
          e2 (r/eventstream :label "e2")
          c  (r/merge e1 e2)]
      (complete! e1)
      (complete! e2)
      (wait)
      (is (rn/completed? c)))))


(deftest reduce-t
  (let [r      (atom [])
        values (range 1 5)
        e      (r/eventstream)
        c      (->> e (r/reduce + 0) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) values))
    (is (= [] @r))
    (complete! e)
    (wait)
    (is (= [10] @r))))


(deftest remove-test
  (let [r        (atom [])
        values   (range 10)
        expected (remove odd? values)
        e        (r/eventstream)
        c        (->> e (r/remove odd?) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) values))
    (is (= [0 2 4 6 8] @r))))


(deftest scan-test
  (let [r      (atom [])
        values (range 1 5)
        e      (r/eventstream)
        c      (->> e (r/scan + 0) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) values))
    (is (= [1 3 6 10] @r))))


(deftest sliding-buffer-test
  (let [r      (atom [])
        values (range 6)
        e      (r/eventstream)
        c      (->> e (r/sliding-buffer 3) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) values))
    (is (= [[0] [0 1] [0 1 2] [1 2 3] [2 3 4] [3 4 5]] @r))))


(deftest snapshot-test
  (let [r   (atom [])
        b   (r/behavior 42)
        e   (r/eventstream)
        c   (->> e (r/snapshot b) (r/swap! r conj))]
    (push-and-wait! e 1 b 43 e 2 e 3)
    (is (= [42 43 43] @r))))


(deftest switch-test
  (let [r   (atom [])
        e1  (r/eventstream :label "e1")
        e2  (r/eventstream :label "e2")
        sw  (r/eventstream :label "streams")
        c   (->> sw r/switch (r/swap! r conj))]
    (push-and-wait! e1 "A" e1 "B" e2 "C" sw e2 sw e1)
    (is (= ["C" "A" "B"] @r))))


(deftest take-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/take 2) (r/swap! r conj))]
    (push-and-wait! e :foo e :bar e :baz)
    (is (= [:foo :bar] @r))
    (is (rn/completed? c))
    (is (rn/pending? e))))


(deftest take-last-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e (r/take-last 3) (r/swap! r conj))]
    (apply push-and-wait! (interleave (repeat e) (range 5)))
    (is (= [] @r))
    (complete! e)
    (wait)
    (is (= [2 3 4] @r))))


(deftest take-while-test
  (testing "Take while condition returns true"
    (let [r  (atom [])
          e  (r/eventstream)
          c  (->> e (r/take-while (partial >= 5)) (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e) (range 10)))
      (is (= [0 1 2 3 4 5] @r))
      (is (rn/completed? c))))
  (testing "Completes empty when condition fails immediatly"
    (let [r  (atom [])
          e  (r/eventstream)
          c  (->> e (r/take-while (partial >= 5)) (r/swap! r conj))]
      (push-and-wait! e 6)
      (is (= [] @r))
      (is (rn/completed? c))))
  (testing "Take while completes when underlying stream completes"
    (let [e  (r/eventstream)
          c  (r/take-while (partial >= 5) e)]
      (complete! e)
      (wait)
      (is (rn/completed? c)))))


(deftest throttle-test
  (testing "Sending all at once"
    (let [r  (atom [])
          e  (r/eventstream)
          c  (->> e (r/throttle identity 500 10) (r/swap! r conj))]
      (push-and-wait! e :foo e :bar e :baz)
      (is (= [] @r))
      (is (not (rn/pending? e)))
      (wait 500)
      (is (= [[:foo :bar :baz]] @r))))
  (testing "Too many items for the throttle queue to hold"
    (let [r  (atom [])
          e  (r/eventstream)
          c  (->> e (r/throttle identity 300 5) (r/swap! r conj))]
      (apply push-and-wait! (interleave (repeat e) (range 7)))
      (is (= [] @r))
      (is (rn/pending? e))
      (wait 200)
      (is (= [[0 1 2 3 4]] @r))
      (wait 400)
      (is (= [[0 1 2 3 4] [5 6]] @r)))))


(deftest unsubscribe-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e
                (r/map inc)
                (r/subscribe :s (partial swap! r conj)))]
    (push-and-wait! e 1 e 2 e 3)
    (is (= [2 3 4] @r))
    (r/unsubscribe :s c)
    (push-and-wait! e 1 e 2 e 3)
    (is (= [2 3 4] @r))))


;; ---------------------------------------------------------------------------
;; Tests for expression lifting


(deftest lift-fn-test
  (let [x (r/behavior 2 :label "x")
        y (r/behavior 2 :label "y")
        z (r/lift (* 3 x y))]
    (wait)
    (is (= @z 12))
    (push-and-wait! x 3 y 1)
    (is (= @z 9))))


(deftest lift-if-test
  (let [x (r/behavior 2 :label "x")
        z (r/lift (if (> x 2) (dec x) (inc x)))]
    (wait)
    (is (= @z 3))
    (push-and-wait! x 3)
    (is (= @z 2))))


(deftest lift-let-test
  (let [x (r/behavior 2 :label "x")
        z (r/lift (let [y (+ x 2)] (+ x y)))]
    (wait)
    (is (= @z 6))
    (push-and-wait! x 3)
    (is (= @z 8))))


(deftest lift-cond-test
  (let [x (r/behavior 2 :label "x")
        z (r/lift (cond
                   (<= x 1) (+ 9 x)
                   (<= x 4) (+ 4 x)
                   :else    x))]
    (wait)
    (is (= @z 6))
    (push-and-wait! x 4)
    (is (= @z 8))
    (push-and-wait! x 10)
    (is (= @z 10))))


(deftest lift-case-test
  (testing "Happy cases"
    (let [x (r/behavior 2 :label "x")
          y (r/behavior "none" :label "y")
          z (r/lift (+ x (case y
                           "none"         0
                           ("few" "low")  1
                           "high"        10
                           "x"            x
                           5)))]
      (wait)
      (is (= @z 2))
      (push-and-wait! y "high")
      (is (= @z 12))
      (push-and-wait! y "nope")
      (is (= @z 7))
      (push-and-wait! y "x")
      (is (= @z 4))
      (push-and-wait! x 42)
      (is (= @z 84))))
  (testing "Error case"
    (let [x (r/behavior 1)
          y (r/behavior 0)
          z (r/lift (case (+ x y)
                      1       "low"
                      (2 3 4) "mid"
                      5       "high"))
          error (r/behavior nil)]
      (r/err-into error z)
      (wait)
      (is (= @z "low"))
      (push-and-wait! y 3)
      (is (= @z "mid"))
      (push-and-wait! x 3)
      (is (= (-> error deref :exception type) IllegalArgumentException)))))


(deftest lift-and-test
  (let [x (r/behavior true :label "x")
        y (r/behavior false :label "y")
        z (r/lift (and x y))]
    (wait)
    (is (not @z))
    (push-and-wait! y 1)
    (is @z)
    (push-and-wait! x nil)
    (is (not @z))))


(deftest lift-or-test
  (let [x (r/behavior true :label "x")
        y (r/behavior false :label "y")
        z (r/lift (or x y))]
    (wait)
    (is @z)
    (push-and-wait! x nil)
    (is (not @z))
    (push-and-wait! y 1)
    (is @z)))


;; ---------------------------------------------------------------------------
;; Tests of error handling

(defn- id-or-ex
  [x]
  (if (= 42 x)
    (throw (IllegalArgumentException. (str x)))
    x))


(deftest err-ignore-test
  (let [r (atom [])
        e (r/eventstream)
        c (->> e
               (r/map id-or-ex)
               r/err-ignore
               (r/swap! r conj))]
    (push-and-wait! e 1 e 2 e 42 e 3)
    (is (= [1 2 3] @r))))


(deftest err-retry-after-test
  (let [r (atom [])
        n (atom 2)
        e (r/eventstream)
        c (->> e
               (r/map (fn [x] (if (and (= x 42)
                                       (< 0 (swap! n dec)))
                                (throw (IllegalArgumentException. (str x)))
                                x)))
               (r/err-retry-after 50)
               (r/swap! r conj))]
    (push-and-wait! e 1 e 2 e 42 e 3)
    (is (= 0 @n))
    (is (= [1 2 3 42] @r))))


(deftest err-return-test
  (let [r  (atom [])
        e  (r/eventstream)
        c  (->> e
               (r/map id-or-ex)
               (r/err-return 99)
               (r/swap! r conj))]
    (push-and-wait! e 1 e 2 e 42 e 3)
    (is (= [1 2 99 3] @r))))


(deftest err-switch-test
  (let [r  (atom [])
        e1 (r/eventstream :label "e1")
        e2 (r/seqstream (range 5) :label "e2")
        c (->> e1
               (r/map id-or-ex)
               (r/err-switch e2)
               (r/swap! r conj))]
    (push-and-wait! e1 1 e1 2 e1 42 e1 3)
    (is (= [1 2 0 1 2 3 4] @r))))


(deftest err-into-test
  (let [r      (atom [])
        errors (r/eventstream :label "errors")
        e      (r/eventstream :label "e")
        c      (->> e
                    (r/map id-or-ex)
                    (r/err-into errors)
                    (r/swap! r conj))]
    (push-and-wait! e 1 e 2 e 42 e 3)
    (is (= [1 2 3] @r))
    (is (instance? IllegalArgumentException (-> errors deref :exception)))))
