(ns reactor.core
  (:refer-clojure :exclude [filter merge map reduce time])
  (:require [reactor.propagation :as p]
            [reactor.execution :as x]))

;; Concepts:
;; An event is something non-continuous that "happens".
;; 
;; An occurence is a pair [event timestamp].
;; 
;; An event source publishes occurences to subscribers.
;; 
;; A signal (a.k.a behaviour) is a value that possibly changes over time.
;; 
;; A reactive is an abstraction over event source and signal.
;; 
;; A follower is a function or reactive that is affected by events or value changes.



;; types and default implementations

(defrecord Occurence [event timestamp])

(defprotocol Reactive
  (subscribe [r f followers]
    "Subscribes a one-argument listener function that influences the followers.
     In case of an event source the listener fn is invoked with an Occurence instance.
     In case of a signal the listener fn is invoked with the new value of the signal.")
  (unsubscribe [r f]
    "Removes the listener fn from the list of followers.")
  (followers [r]
    "Returns reactives that follow this reactive.")
  (role [r]
    "Returns a keyword denoting the functional role of the reactive."))


(def ^:private reactive-fns
  {:subscribe (fn [r f followers]
                (p/add! (.ps r) (p/propagator f followers))
                r)
   :unsubscribe (fn [r f]
                  (doseq [p (->> (.ps r) p/propagators (clojure.core/filter #(= (:fn %) f)))]
                    (p/remove! (.ps r) p))
                  r)
   :followers (fn [r]
                (mapcat :targets (p/propagators (.ps r))))
   :role (fn [r] (.role r))})



(defprotocol Signal  
  (getv [this]
    "Returns the current value of this signal.")
  (setv! [this value]
    "Sets the value of this signal."))

(deftype DefaultSignal [role a ps executor]
  Signal
  (getv [_]
    (deref a))
  (setv! [_ value]
    (x/schedule executor #(reset! a value))))

(extend DefaultSignal Reactive reactive-fns)


(deftype Time [role a executor ps]
  Signal
  (getv [_]
    (deref a))
  (setv! [_ value]
    (throw (UnsupportedOperationException. "A time signal cannot be set"))))

(extend Time Reactive reactive-fns)


(defprotocol EventSource
  (raise-event! [this evt]
    "Sends a new event."))

(deftype DefaultEventSource [role ps executor]
  EventSource
  (raise-event! [_ evt]
    (x/schedule executor #(p/propagate-all! ps (Occurence. evt (System/currentTimeMillis))))))

(extend DefaultEventSource Reactive reactive-fns)



;; factories for signals and event sources

(defn signal
  "Creates new signal with the given initial value."
  ([initial-value]
     (signal :signal x/current-thread initial-value))
  ([role initial-value]
     (signal role x/current-thread initial-value))
  ([role executor initial-value]
     (let [a (atom initial-value)
           ps (p/propagator-set)]
       (add-watch a :signal (fn [ctx key old new]
                              (if (not= old new)
                                (p/propagate-all! ps new))))
       (DefaultSignal. role a ps executor))))


(defonce ^:private timer-signals (atom #{}))

(defn time
  "Creates time signal that updates its value every resolution ms."
  [resolution]
  (let [a (atom (System/currentTimeMillis))
        executor (x/timer-executor resolution)
        ps (p/propagator-set)
        newsig (Time. :time a executor ps)]
    (add-watch a :signal (fn [ctx key old new]
                           (if (not= old new)
                             (p/propagate-all! ps new))))
    (x/schedule executor #(reset! a (System/currentTimeMillis)))
    (swap! timer-signals #(conj % newsig))
    newsig))


(defn eventsource
  "Creates a new event source."
  ([]
     (eventsource :eventsource x/current-thread))
  ([role]
     (eventsource role x/current-thread))
  ([role executor]
     (DefaultEventSource. role (p/propagator-set) executor)))

;; common functions for reactives (signals and event sources)

(defn pass
  "Creates a reactive of the same type as the given reactive.
   Uses the specified executor to handle the event or value propagation in a different thread."
  [executor reactive]
  (cond
   (satisfies? Signal reactive)
   (let [newsig (signal :signalpass executor nil)]
     (subscribe reactive #(setv! newsig %) [newsig])
     newsig)
   (satisfies? EventSource reactive)
   (let [newes (eventsource :eventpass executor)]
     (subscribe reactive #(raise-event! newes (:event %)) [newes])
     newes)))


;; combinators for event sources

(defn as-signal
  "Returns the given argument, if it is already a signal.
   If the given argument is an event source, returns a new
   signal that stores the last event as value. The initial
   value of the new signal is nil.
   Otherwise returns a signal that contains the argument as value."
  [sig-or-val]
  (cond
   (satisfies? Signal sig-or-val)
   sig-or-val
   (satisfies? EventSource sig-or-val)
   (let [newsig (signal :eventsink nil)]
     (subscribe sig-or-val #(setv! newsig (:event %)) [newsig])
     newsig)
   :else (signal sig-or-val)))


(defn map
  "Creates a new event source that raises an event
   whenever the given event source raises an event. The new
   event is created by applying a transformation to the original
   event.
   If the transform-fn-or-value parameter evaluates to a function
   it is invoked with the original event as argument. Otherwise
   the second argument is raised as the new event."
  [transform-fn-or-value evtsource]
  (let [newes (eventsource :map)]
    (subscribe evtsource
               #(raise-event!
                 newes
                 (if (fn? transform-fn-or-value)
                   (transform-fn-or-value (:event %))
                   transform-fn-or-value))
               [newes])
    newes))


(defn filter
  "Creates a new event source that only raises an event
   when the predicate returns true for the original event."
  [pred evtsource]
  (let [newes (eventsource :filter)]
    (subscribe evtsource #(when (pred (:event %))
                              (raise-event! newes (:event %)))
               [newes])
    newes))


(defn merge
  "Produces a new event source from others, so that the
   new event source raises an event whenever one of the
   specified sources raises an event."
  [& evtsources]
  (let [newes (eventsource :merge)]
    (doseq [es evtsources]
      (subscribe es #(raise-event! newes (:event %)) [newes]))
    newes))


(defn switch
  "Creates a signal that initially behaves like the given signal sig.
   Upon any occurence of the given event source the signal switches to
   behave like the signal that the occurence contained."
  [sig-or-value evtsource]
  (let [sig (as-signal sig-or-value)
        newsig (signal :switch (getv sig))
        sig-listener #(setv! newsig %)
        switcher (fn [{timestamp :timestamp evtsig :event}]
                   {:pre [(instance? reactor.core.Signal evtsig)]}
                   (unsubscribe sig sig-listener)
                   (subscribe evtsig sig-listener [newsig])
                   (setv! newsig (getv evtsig)))]
    (subscribe sig sig-listener [newsig])
    (subscribe evtsource switcher [newsig])
    newsig))

(defn reduce
  "Creates a signal from an event source. On each event
   the given function is invoked with the current signals
   value as first and the event as second parameter.
   The result of the function is set as new value of the signal."
  ([f evtsource]
     (reduce f nil))
  ([f value evtsource]
     (let [newsig (signal :reduce value)]
       (subscribe evtsource #(setv! newsig (f (getv newsig) (:event %))) [newsig])
       newsig)))


(defn react-with
  "Subscribes f as listener to the event source and
   returns the event source. The function f receives
   the occurence as argument, any return value is discarded."
  [f evtsource]
  (subscribe evtsource f []))


;; combinators for signals


(defn trigger
  "Creates an event source from a signal so that an event is raised
   whenever the signal value changes. If the evt-or-fn argument
   evaluates to a function, then it is applied to the signals
   new value. An event is raised when the function return a non-nil
   result. If evt-or-fn is not a function it is assumed to be the
   event that will be raised on signal value change."
  ([sig]
     (trigger identity sig))
  ([evt-or-fn sig]
  (let [newes (eventsource :trigger)]
    (subscribe sig
               (fn [new]
                 (if-let [evt (if (fn? evt-or-fn)
                                (evt-or-fn new)
                                evt-or-fn)]
                   (raise-event! newes evt)))
               [newes])
    newes)))


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
      output-values)))


(defn as-vector
  "Returns a value vector from a collection of values or a single value."
  [values]
  (if (vector? values)
    values
    (if (list? values)
      (vec values)
      (vector values))))


(defn bind!
  "Connects n input-signals with m output-signals so that on
   each change of an input signal value the values in the output signals
   are re-calculated by the function f. Function f must accept n
   arguments and must either return a vector of m values or a single
   non-seq value."
  [f input-sigs output-sigs]
  (let [calc-outputs (lift-helper f input-sigs)
        listener-fn (fn [_]
                      (some->> (calc-outputs)
                               as-vector
                               (setvs! output-sigs)))]
    (doseq [sig input-sigs]
      (subscribe sig listener-fn output-sigs))
    (listener-fn nil)) ; initial value sync
  output-sigs)


(defn lift
  "Creates a signal that is updated by applying the n-ary function
   f to the values of the input signals whenever one value changes."
  [f & sigs]
  (let [newsig (signal :lift 0)]
    (bind! f (vec (clojure.core/map as-signal sigs)) [newsig])
    newsig))


(defn process-with
  "Connects a n-ary function to n input signals so that the function is
   executed whenever one of the signals changes its value. The output of the
   function execution is discarded."
  [f & input-sigs]
  (bind! f (vec input-sigs) nil))


(defn stop-timer
  "Stops the time signal from being updated."
  [tsig]
  (-> tsig .executor x/shutdown)
  (swap! timer-signals #(disj % tsig)))


(defn stop-all-timers
  "Stops all time signals at once."
  []
  (doseq [t @timer-signals]
    (-> t .executor x/shutdown))
  (reset! timer-signals #{}))


