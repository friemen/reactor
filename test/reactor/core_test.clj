(ns reactor.core-test
  (:require [reactor.core :as r]
            [reactor.execution :as x])
  (:use clojure.test))

;; fixture

(defn reset-engine
  [f]
  (r/reset-engine!)
  (alter-var-root #'r/auto-execute (constantly 10))
  (f))

(use-fixtures :each reset-engine)

;; subscribe / unsubscribe test

(deftest subscription-test
  (testing "Subscribe / Unsubscribe by follower"
    (let [es (r/eventsource)]
      (r/subscribe es :nil nil?)
      (is (= '(:nil) (r/followers es)))
      (r/unsubscribe es constantly) ; this must have no effect
      (is (= '(:nil) (r/followers es)))
      (r/unsubscribe es :nil)
      (is (empty? (r/followers es)))))
  (testing "Subscribe / Unscribe by fn"
    (let [s (r/signal 0)
          pr-fn #(println %)]
      (r/subscribe s :foo pr-fn)
      (is (= '(:foo) (r/followers s)))
      (r/unsubscribe s pr-fn)
      (is (empty? (r/followers s)))))
  (testing "Subscribe / Unsubscribe all"
    (let [es (r/eventsource)]
      (r/subscribe es :nil nil?)
      (is (= '(:nil) (r/followers es)))
      (r/unsubscribe es nil)
      (is (empty? (r/followers es))))))


;; tests for combinators on eventsources

(deftest pass-test
  (let [exec1used (atom false)
        exec1 (reify reactor.execution.Executor
               (schedule [_ f] (reset! exec1used true) (f)))
        e1 (r/eventsource)
        sig1 (->> e1 (r/pass exec1) r/hold)
        exec2used (atom false)
        exec2 (reify reactor.execution.Executor
               (schedule [_ f] (reset! exec2used true) (f)))
        sig2 (->> sig1 (r/pass exec2))]
    (r/raise-event! e1 "Foo")
    (is @exec1used) ; check that executor 1 was actually used
    (is (= "Foo" (r/getv sig1)))
    (is @exec2used) ; check that executor 2 was actually used
    (is (= "Foo" (r/getv sig2)))))


(deftest hold-test
  (let [e (r/eventsource)
        s1 (->> e r/hold)
        s2 (->> e (r/hold "Bar"))]
    (is (= nil (r/getv s1)))
    (is (= "Bar" (r/getv s2)))
    (r/raise-event! e "Foo")
    (is (= "Foo" (r/getv s1)))
    (is (= "Foo" (r/getv s2)))))


(deftest map-test
  (let [e1 (r/eventsource)
        e2 (->> e1 (r/map (partial * -1)))
        s2 (->> e2 r/hold)
        e3 (->> e1 (r/map 42))
        s3 (->> e3 r/hold)]
    (r/raise-event! e1 13)
    (is (= -13 (r/getv s2)))
    (is (= 42 (r/getv s3)))))


(deftest filter-test
  (let [e1 (r/eventsource)
        e2 (->> e1 (r/filter #(not= "Foo" %)))
        sig (->> e2 r/hold)]
    (is (nil? (r/getv sig)))
    (r/raise-event! e1 "Foo")
    (is (nil? (r/getv sig))) ; ensure that Foo was filtered out
    (r/raise-event! e1 "Bar")
    (is (= "Bar" (r/getv sig))))) ; check that Bar passed


(deftest delay-test
  (let [e1 (r/eventsource)
        s (->> e1 (r/delay 1000) r/hold)]
    (r/raise-event! e1 "Bar")
    (x/wait 200)
    (is (= nil (r/getv s)))
    (r/raise-event! e1 "Foo")
    (x/wait 900)
    (is (= "Bar" (r/getv s)))
    (x/wait 200)
    (is (= "Foo" (r/getv s)))))


(deftest calm-test
  (let [e (r/eventsource)
        sum (->> e (r/calm 50) (r/reduce + 0))]
    (r/raise-event! e 1)
    (x/wait 10)
    (r/raise-event! e 40) ; cancels the first event
    (x/wait 60)
    (r/raise-event! e 2)
    (x/wait 60)
    (is (= 42 (r/getv sum)))))


(deftest merge-test
  (let [e1 (r/eventsource)
        e2 (r/eventsource)
        e3 (r/merge e1 e2)
        s3 (->> e3 r/hold)]
    (r/raise-event! e1 42)
    (is (= 42 (r/getv s3)))
    (r/raise-event! e2 13)
    (is (= 13 (r/getv s3)))))


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


(deftest reduce-test
  (let [e (r/eventsource)
        sum (->> e (r/reduce + 0))]
    (r/raise-event! e 1)
    (r/raise-event! e 41)
    (is (= 42 (r/getv sum)))))


(deftest reduce-t-test
  (let [speed (r/signal 0)
        ticks (r/eventsource)
        pos (->> (r/merge (->> speed
                               r/changes
                               (r/map first))
                          (->> ticks
                               (r/map second)))
                 (r/reduce-t (fn [pos s elapsed]
                               (+ pos (* (or s 0) elapsed)))
                             0))]
    (r/setv! speed 5)
    (x/wait 10)
    (r/raise-event! ticks nil)
    (is (>= 60 (r/getv pos)))))


(deftest snapshot-test
  (let [e (r/eventsource)
        s1 (r/signal 42)
        s2 (->> e (r/snapshot s1) (r/hold 13))]
    (is (= 13 (r/getv s2)))
    (r/raise-event! e "Foo")
    (is (= 42 (r/getv s2)))))


;; tests for combinators on signals

(deftest behind-test
  (let [s1 (r/signal 0)
        s2 (r/behind 2 s1)]
    (is (= 0 (r/getv s2)))
    (r/setv! s1 1) (r/setv! s1 2)
    (is (= 0 (r/getv s2)))
    (r/setv! s1 3)
    (is (= 1 (r/getv s2)))))


(deftest changes-test
  (let [n (r/signal 0)
        alarm-events (->> n (r/changes #(when (> % 10) "ALARM!")))
        alarm-signal (->> alarm-events (r/map second) r/hold)]
    (r/setv! n 9)
    (is (= nil (r/getv alarm-signal))) ; no ALARM must be set
    (r/setv! n 11)
    (is (= "ALARM!" (r/getv alarm-signal))))) ; check that ALARM is set


(deftest setvs!-test
  (let [sigs (map #(r/signal %) [1 2 3 4])]
    (r/setvs! sigs [41 42 43])
    (is (= [41 42 43 4] (map r/getv sigs)))))


(deftest bind!-test
  (let [inputsigs [(r/signal 0) (r/signal 0)]
        prod (r/signal nil)]
    (r/bind! (fn [n1 n2] (* n1 n2))
             inputsigs
             prod)
    (r/setvs! inputsigs [2 3])
    (is (= 6 (r/getv prod)))))


(deftest apply-test
  (let [n1 (r/signal 0)
        n2 (r/signal 0)
        n1half (r/apply / [n1 2]) ; always contains the half of n1's value 
        sum (r/apply + [n1 n2]) ; always contains the sum of n1 and n2
        sum>10 (->> sum
                    (r/changes #(when (> % 10) "ALARM!"))
                    (r/react-with #(println %)))]
    (r/setv! n1 4)
    (is (= 4 (r/getv sum))) ; check that sum is up-to-date
    (is (= 2 (r/getv n1half))) ; check that n1half is up-to-date
    (r/setv! n2 8)
    (is (= 12 (r/getv sum))))) ; check that sum is up-to-date


(deftest lift-let-test
  (let [n1 (r/signal 2)
        n2 (r/signal 3)
        n1*3+n2 (r/lift (let [n1*2 (+ n1 n1)
                              n1*3 (+ n1*2 n1)]
                          (+ n2 n1*3)))]
    (is (= 9 (r/getv n1*3+n2)))
    (r/setvs! [n1 n2] [3 4])
    (is (= 13 (r/getv n1*3+n2)))))


(deftest lift-if-test
  (let [n1 (r/signal -1)
        n2 (r/signal 1)
        ifelses (r/lift (if (> n1 0) n2 (+ n1 n2)))
        ifs (r/lift (if (< n1 0) n1))]
    (is (= 0 (r/getv ifelses)))
    (is (= -1 (r/getv ifs)))
    (r/setvs! [n1 n2] [2 5])
    (is (= 5 (r/getv ifelses)))
    (is (= nil (r/getv ifs)))
    (r/setv! n2 6)
    (is (= (r/getv ifelses)))))


(deftest lift-and-test
  (let [b1 (r/signal true)
        b2 (r/signal false)
        ands (r/lift (and b1 b2))]
    (is (= false (r/getv ands)))
    (r/setvs! [b1 b2] [1 true])
    (is (= true (r/getv ands)))))


(deftest lift-or-test
  (let [b1 (r/signal true)
        b2 (r/signal false)
        ors (r/lift (or b1 b2))]
    (is (= true (r/getv ors)))
    (r/setvs! [b1 b2] [1 true])
    (is (= 1 (r/getv ors)))
    (r/setvs! [b1 b2] [nil false])
    (is (= false (r/getv ors)))))


(deftest lift-test
  (testing "Simple expression lifting"
    (let [n1 (r/signal 0)
          plus10*2 (r/lift (* 2 (+ 10 n1)))]
      (r/setv! n1 4)
      (is (= 28 (r/getv plus10*2)))))
  (testing "Expressions with a reference to the new signal <S>"
    (binding [r/auto-execute 0]
      (let [time (r/signal 0)
            elapsed (->> time
                         r/changes
                         (r/map (fn [[t-1 t]] (- t t-1)))
                         (r/hold 0))
            s (r/lift (+ <S> (* elapsed 2)))]
        (is (= 0 (r/getv s)))
        (r/setv! time 2)
        (r/execute!)
        (is (= 4 (r/getv s))))))
  (testing "Expression with a reference to elapsed time r/etime"
    (r/start-engine! 40)
    (let [s (r/signal 0)
          y (r/lift 0 (+ <S> (* s r/etime 1000)))]
      (is (= 0 (r/getv y)))
      (r/setv! s 1)
      (x/wait 150)
      (r/stop-engine!)
      #_(println (r/getv y))
      (is (and (< 100 (r/getv y)) (< (r/getv y) 170))))))



;; naive state machine implementation for reduce test

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

;; test demonstrating how a statemachine can be used
;; in conjunction with event sources and reduce

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


;; test of external propagation

(deftest heights-test
  (let [r (r/signal 2)
        q (r/signal 1)
        s (r/lift (+ r q <S>))
        e (r/changes s)
        t (r/hold e)]
    (let [rhm (r/heights [r q])]
      (is (= 0 (rhm t)))
      (is (= 1 (rhm e)))
      (is (= 2 (rhm s)))
      (is (= 4 (rhm r) (rhm q))))
    (let [rhm (r/heights [e])]
      (is (nil? (rhm s)))
      (is (= 0 (rhm t)))
      (is (= 1 (rhm e))))))

;; test for registration in reactives and disposal


(deftest exceptions-es-test
  (let [throwing (fn [_] (throw (IllegalArgumentException. "Test Dummy Exception")))
        s (r/signal 0)
        last-ex (->> r/exceptions r/hold)]
    (->> s (r/process-with throwing))
    (r/setv! s 1)
    (is (instance? IllegalArgumentException (r/getv last-ex)))))


(deftest registration-test
  (reset! r/reactives r/default-reactives)
  (let [s (r/signal 0)]
    (is ((-> r/reactives deref :active) s))
    (is (empty? (-> r/reactives deref :disposed)))))


(deftest dispose-test
  (reset! r/reactives r/default-reactives)
  (let [s (r/signal 0)
        t (->> s r/changes r/hold)]
    (r/dispose! t)
    (is (r/disposed? t))
    (is (not (r/disposed? s)))))


(deftest unlink-test
  (reset! r/reactives r/default-reactives)
  (let [s (r/signal 0)
        t (->> s r/changes r/hold)]
    (is (= 6 (-> r/reactives deref :active count)))
    (r/dispose! t)
    (r/unlink!) ; remove t
    (r/unlink!) ; remove the changes event source
    (is (= 4 (-> r/reactives deref :active count)))
    (r/unlink!) ; must not have any effect
    (is (= 4 (-> r/reactives deref :active count)))
    (is (not (r/disposed? s)))
    (is (r/disposed? t))))
