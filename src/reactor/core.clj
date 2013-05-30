(ns reactor.core
  (:refer-clojure :exclude [filter merge map reduce time])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

;; Concepts:
;; An event is something non-continuous that "happens".
;;
;; An occurence is a pair [event timestamp].
;;
;; A signal (a.k.a behaviour) is a value that possibly changes
;; over time.
;;
;; The purpose of the factories and combinators as implemented below
;; is to enable declarative specifications of event and signal
;; processing chains (using the ->> macro).
;;
;; Example for event processing:
;;
;; (def e1 (r/eventsource))
;; (def e2 (r/eventsource))
;;
;; (->> (r/merge e1 e2)
;;      (r/filter #(not= % "World"))
;;      (r/react-with #(println "EVENT:" %)))
;;
;; (r/raise-event! e1 "Hello")
;; (r/raise-event! e2 "World")
;; => prints "Hello"
;;
;; Example for signal processing:
;;
;; (def n1 (r/signal 0))
;; (def n2 (r/signal 0))
;;
;; (def sum (->> (r/lift + n1 n2)))
;; (r/setvs! [n1 n2] [3 7])
;; => sum == 10, and sum is updated whenever n1 or n2 changes.
;;
;; (def sum>10 (->> sum
;;                 (r/trigger #(when (> % 10) "ALARM!"))
;;                 (r/react-with #(println %))))
;; => sum>10 is an event source. whenever sum's value > 10
;;    an occurence with "ALARM!" is printed.
;;


;; TODOs
;; implement executor management / timer shutdown


(defrecord Occurence [event timestamp])

(defprotocol EventSource
  (subscribe [this event-listener]
    "Subscribes event-listener fn to event source.
     The event-listener is invoked with a pair [event timestamp-in-ms] as argument.")
  (unsubscribe [this event-listener]
    "Removes the event-listener fn from the list of subscribers.")
  (raise-event! [this evt]
    "Sends a new event."))

(defprotocol Signal
  (add-listener [this signal-listener]
    "Subscribes a signal-listener fn to this signal.
     The signal-listener is invoked when the signals value changes.
     The signal-listener function receives the new
     value of the signal as only argument.")
  (remove-listener [this signal-listener]
    "Removes the signal-listener fn from the list of subscribers.")
  (getv [this]
    "Returns the current value of this signal.")
  (setv! [this value]
    "Sets the value of this signal."))


;; factories for event sources

(defn eventsource
  "Creates a new event source."
  []
  (let [a (atom nil)]
    (reify EventSource
      (subscribe [this event-listener]
        (add-watch a event-listener (fn [ctx key old new]
                                      (event-listener new)))
        this)
      (unsubscribe [this event-listener]
        (remove-watch a event-listener)
        this)
      (raise-event! [_ evt]
        (reset! a (Occurence. evt (System/currentTimeMillis)))))))


(defn timer
  "Creates timer event source that creates occurence every resolution ms."
  [resolution evt]
  (let [newes (eventsource)
        executor (ScheduledThreadPoolExecutor. 1)]
    (.scheduleAtFixedRate executor
                          #(raise-event! newes evt)
                          0
                          resolution
                          TimeUnit/SECONDS)
    newes))


;; factories for signals


(defn signal
  "Creates new signal with the given initial-value."
  [initial-value]
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
      (getv [_]
        (deref a))
      (setv! [_ value]
        (reset! a value)))))


(defn time
  "Creates time signal that updates its value every resolution ms."
  [resolution]
  (let [newsig (signal)
        executor (ScheduledThreadPoolExecutor. 1)]
    (.scheduleAtFixedRate executor
                          #(setv! newsig (System/currentTimeMillis))
                          0
                          resolution
                          TimeUnit/SECONDS)
    newsig))

;; combinators for event sources

(defn map
  "Creates a new event source that raises an event
   whenever the given event source raises an event. The new
   event is created by applying a transformation to the original
   event.
   If the transform-fn-or-value parameter evaluates to a function
   it is invoked with the original event as argument. Otherwise
   the second argument is raised as the new event."
  [transform-fn-or-value evtsource]
  (let [newes (eventsource)]
    (subscribe evtsource
               #(raise-event!
                 newes
                 (if (fn? transform-fn-or-value)
                   (transform-fn-or-value (:event %))
                   transform-fn-or-value)))
    newes))


(defn filter
  "Creates a new event source that only raises an event
   when the predicate returns true for the original event."
  [pred evtsource]
  (let [newes (eventsource)]
    (subscribe evtsource #(when (pred (:event %))
                              (raise-event! newes (:event %))))
    newes))


(defn merge
  "Produces a new event source from others, so that the
   new event source raises an event whenever one of the
   specified sources raises an event."
  [& evtsources]
  (let [newes (eventsource)]
    (doseq [es evtsources]
      (subscribe es #(raise-event! newes (:event %))))
    newes))


(defn switch
  "Converts an event source to a signal. The signal will
   hold the last event. The signals value is initially set
   to the given value."
  ([evtsource]
     (switch nil evtsource))
  ([value evtsource]
     (let [newsig (signal value)]
       (subscribe evtsource #(setv! newsig (:event %)))
       newsig)))


(defn reduce
  "Converts an event source to a signal. On each event
   the given function is invoked with the current signals
   value as first and the event as second parameter.
   The result of the function is set as new value of the signal."
  ([f evtsource]
     (reduce f nil))
  ([f value evtsource]
     (let [newsig (signal value)]
       (subscribe evtsource #(setv! newsig (f (getv newsig) (:event %))))
       newsig)))


(defn react-with
  "Subscribes f as listener to the event source and
   returns the event source. The function f receives
   the occurence as argument, any return value is discarded."
  [f evtsource]
  (subscribe evtsource f))


;; combinators for signals


(defn trigger
  "Creates an event source from a signal so that an event is raised
   whenever the signal value changes. If the evt-or-fn argument
   evaluates to a function, then it is applied to the signals
   new value. An event is raised when the function return a non-nil
   result. If evt-or-fn is not a function it is assumed to be the
   event that will be raised on signal value change."
  [evt-or-fn sig]
  (let [newes (eventsource)]
    (add-listener sig
                  (fn [old new]
                    (if-let [evt (if (fn? evt-or-fn)
                                   (evt-or-fn new)
                                   evt-or-fn)]
                      (raise-event! newes evt))))
    newes))


(defn setvs!
  "Sets each output-signal to the respective value."
  [output-signals values]
  (doseq [sv (clojure.core/map vector output-signals values)]
    (setv! (first sv) (second sv))))


(defn- lift-helper
  "Returns a 0-arg function that applies the given function f to the
   current values of all signals."
  [f sigs]
  (fn []
    (let [input-values (clojure.core/map getv sigs)
          output-values (apply f input-values)]
      (println input-values "-->" output-values)
      output-values)))


(defn as-vector
  "Returns a value vector from a collection of values or a single value."
  [values]
  (if (vector? values)
    values
    (if (list? values)
      (vec values)
      (vector values))))


(defn as-signal
  "Returns the given argument, if it is already a signal, otherwise
   returns a signal that contains the argument as value."
  [sig-or-val]
  (if (instance? reactor.core.Signal sig-or-val) sig-or-val (signal sig-or-val)))


(defn bind!
  "Connects n input-signals with m output-signals so that on
   each change of an input signal value the values in the output signals
   are re-calculated by the function f. Function f must accept n
   arguments and must either return a vector of m values or a single
   non-seq value."
  [f input-sigs output-sigs]
  (let [calc-outputs (lift-helper f input-sigs)
        listener-fn (fn [old new]
                      (some->> (calc-outputs)
                               as-vector
                               (setvs! output-sigs)))]
    (doseq [sig input-sigs]
      (add-listener sig listener-fn))
    (listener-fn nil nil))
  output-sigs)


(defn lift
  "Creates a signal that is updated by applying the n-ary function
   f to the values of the input signals whenever one value changes."
  [f & sigs]
  (let [newsig (signal 0)]
    (bind! f (vec (clojure.core/map as-signal sigs)) [newsig])
    newsig))


(defn process-with
  "Connects a n-ary function to n input signals so that the function is
   executed whenever one of the signals changes its value. The output of the
   function execution is discarded."
  [f input-sigs]
  (bind! f input-sigs nil))

