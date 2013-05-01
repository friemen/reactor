(ns reactor.core
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

;; Concepts:
;; An event is something non-continuous that "happens".
;;
;; An occurence is a pair [timestamp, event].
;;
;; A signal (a.k.a behaviour) is a value that possibly changes
;; over time.
;;
;; The purpose of the factories and combinators as implemented below
;; is to enable declarative specifications of event and signal
;; processing chains (using the -> macro).
;;
;; Example for event processing:
;;
;; (def e1 (make-eventsource))
;; (def e2 (make-eventsource))
;;
;; (-> (aggregate e1 e2)
;;    (allow #(not= % "World"))
;;    (react-with #(println "EVENT:" %)))
;;
;; (raise-event! e1 "Hello")
;; (raise-event! e2 "World")
;; => prints "Hello"
;;
;; Example for signal processing:
;;
;; (def n1 (make-signal 0))
;; (def n2 (make-signal 0))
;;
;; (def sum (-> (make-signal 0) (bind + n1 n2)))
;; (set-values! [n1 n2] [3 7])
;; => sum == 10, and sum is updated whenever n1 or n2 changes.
;;
;; (def sum>10 (-> sum
;;                (trigger #(when (> % 10) "ALARM!"))
;;                (react-with #(println %))))
;; => sum>10 is an event source. whenever sum's value > 10
;;    the string "ALARM!" is printed.
;;


;; TODOs
;; add filter for signals
;; introduce timestamp to event specific functions
;; implement timer shutdown


(defprotocol EventSource
  (subscribe [this event-listener])
  (unsubscribe [this event-listener])
  (consume-event! [this])
  (raise-event! [this evt]))

(defprotocol Signal
  (add-listener [this signal-listener])
  (remove-listener [this signal-listener])
  (get-value [this])
  (set-value! [this value]))


;; factories for event sources

(defn make-eventsource []
  (let [a (atom nil)]
    (reify EventSource
      (subscribe [this event-listener]
        (add-watch a event-listener (fn [ctx key old new]
                                      (event-listener new)))
        this)
      (unsubscribe [this key]
        (remove-watch a key)
        this)
      (consume-event! [_]
        (let [v (deref a)]
          (reset! a nil)
          v))
      (raise-event! [_ evt]
        (reset! a evt)))))


(defn make-timer
  "Creates timer event source."
  [resolution evt]
  (let [newes (make-eventsource)
        executor (ScheduledThreadPoolExecutor. 1)]
    (.scheduleAtFixedRate executor #(raise-event! newes evt) 0 resolution TimeUnit/SECONDS)
    newes))


;; factories for signals


(defn make-signal [initial-value]
  (let [a (atom initial-value)]
    (reify Signal
      (add-listener [this signal-listener]
        (add-watch a signal-listener (fn [ctx key old new]
                                       (when (not= old new)
                                         (signal-listener old new))))
        this)
      (remove-listener [this signal-listener]
        (remove-watch a signal-listener)
        this)
      (get-value [_]
        (deref a))
      (set-value! [_ value]
        (reset! a value)))))


;; combinators for event sources

(defn transform
  "Creates a new event source that raises an event
   whenever the given event source raises an event. The new
   event is created by applying a transformation to the original
   event.
   If the transform-fn-or-value parameter evaluates to a function
   it is invoked with the original event as argument. Otherwise
   the second argument is raised as the new event."
  [eventsource transform-fn-or-value]
  (let [newes (make-eventsource)]
    (subscribe eventsource
               #(raise-event!
                 newes
                 (if (fn? transform-fn-or-value)
                   (transform-fn-or-value %)
                   transform-fn-or-value)))
    newes))


(defn allow
  "Creates a new event source that only raises an event
   when the predicate returns true for the original event."
  [eventsource pred]
  (let [newes (make-eventsource)]
    (subscribe eventsource #(when (pred %)
                              (raise-event! newes %)))
    newes))


(defn aggregate
  "Produces a new event source from others, so that the
   new event source raises an event whenever one of the
   specified sources raises an event."
  [& eventsources]
  (let [newes (make-eventsource)]
    (doseq [es eventsources]
      (subscribe es #(raise-event! newes %)))
    newes))


(defn switch
  "Converts an event source to a signal. The signal will
   hold the last event. The signals value is initially set
   to the given value."
  ([eventsource]
     (switch eventsource nil))
  ([eventsource value]
     (let [newsig (make-signal value)]
       (subscribe eventsource #(set-value! newsig %))
       newsig)))


(defn react-with
  "Subscribes f as listener to the event source and
   returns the event source."
  [eventsource f]
  (subscribe eventsource f))


;; combinators for signals


(defn trigger
  "Creates an event source from a signal so that an event is raised
   whenever the signal value changes. If the evt-or-fn argument
   evaluates to a function, then it is applied to the signals
   new value. An event is raised when the function return a non-nil
   result. If evt-or-fn is not a function it is assumed to be the
   event that will be raised on signal value change."
  [signal evt-or-fn]
  (let [newes (make-eventsource)]
    (add-listener signal
                  (fn [old new]
                    (if-let [evt (if (fn? evt-or-fn)
                                   (evt-or-fn new)
                                   evt-or-fn)]
                      (raise-event! newes evt))))
    newes))


(defn set-values!
  "Sets each output-signal to the respective value."
  [output-signals values]
  (doseq [sv (map vector output-signals values)]
    (set-value! (first sv) (second sv))))


(defn lift
  "Returns a 0-arg function that applies the given function f to the
   current values of all signals."
  [f signals]
  (fn []
    (let [input-values (map get-value signals)
          output-values (apply f input-values)]
      (println input-values "-->" output-values)
      output-values)))


(defn- as-vector
  "Returns a value vector from a collection of values or a single value."
  [values]
  (if (coll? values) (vec values) (vector values)))


(defn bind
  "Connects input-signals with output-signals so that on
   each change of an input signal value the values in the output signals
   are re-calculated by the function f."
  [output-signals f & input-signals]
  (let [input-signal-vec (as-vector input-signals)
        output-signal-vec (as-vector output-signals)
        calc-outputs (lift f input-signal-vec)
        listener-fn (fn [old new]
                      (some->> (calc-outputs)
                               as-vector
                               (set-values! output-signal-vec)))]
    (doseq [sig input-signal-vec]
      (add-listener sig listener-fn)))
  output-signals)


(defn process-with
  "Connects a function to input signals so that the function is executed
   whenever one of the signals changes its value. The output of the
   function execution is discarded."
  [input-signals f]
  (apply (partial bind nil f) input-signals))




