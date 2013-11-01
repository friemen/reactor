(ns reactor.core
  "Factories and combinators for FRP style signals and event sources."
  (:refer-clojure :exclude [delay filter merge map reduce time])
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

; TODO
; how should errors be handled?
; events should be propagated through a toplogically sorted graph


;; types and default implementations

(defrecord Occurence [event timestamp])

(defprotocol Reactive
  (subscribe [react f followers]
    "Subscribes a one-argument listener function. The followers seq contains reactives
     that are affected by side-effects that the listener function has.
     In case of an event source the listener fn is invoked with an Occurence instance.
     In case of a signal the listener fn is invoked with the new value of the signal.")
  (unsubscribe [react f]
    "Removes the listener fn from the list of followers.")
  (followers [react]
    "Returns reactives that follow this reactive.")
  (role [react]
    "Returns a keyword denoting the functional role of the reactive."))


(def ^:private reactive-fns
  {:subscribe (fn [r f followers]
                (p/add! (.ps r) (p/propagator f followers))
                r)
   :unsubscribe (fn [r f]
                  (doseq [p (->> (.ps r)
                                 p/propagators
                                 (clojure.core/filter #(or (nil? f) (= (:fn %) f))))]
                    (p/remove! (.ps r) p))
                  r)
   :followers (fn [r]
                (mapcat :targets (p/propagators (.ps r))))
   :role (fn [r] (.role r))})



(defprotocol Signal
  (getv [sig]
    "Returns the current value of this signal.")
  (setv! [sig value]
    "Sets the value of this signal and returns the value."))

(defrecord DefaultSignal [role a ps executor]
  Signal
  (getv [_]
    (deref a))
  (setv! [_ value]
    (x/schedule executor #(reset! a value))))

(extend DefaultSignal Reactive reactive-fns)


(defrecord Time [role a executor ps]
  Signal
  (getv [_]
    (deref a))
  (setv! [_ value]
    (throw (UnsupportedOperationException. "A time signal cannot be set"))))

(extend Time Reactive reactive-fns)


(defprotocol EventSource
  (raise-event! [this evt]
    "Sends a new event."))


(defn- as-occ
  [evt-or-occ]
  (if (instance? Occurence evt-or-occ)
    evt-or-occ
    (Occurence. evt-or-occ (System/currentTimeMillis))))


(defrecord DefaultEventSource [role ps executor]
  EventSource
  (raise-event! [_ evt]
    (x/schedule executor #(p/propagate-all! ps (as-occ evt)))))

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
   Uses the specified executor to handle the event or value propagation in a different thread.
   See also protocol Executor in ns reactor.execution."
  [executor react]
  (cond
   (satisfies? Signal react)
   (let [newsig (signal :signalpass executor nil)]
     (subscribe react #(setv! newsig %) [newsig])
     newsig)
   (satisfies? EventSource react)
   (let [newes (eventsource :eventpass executor)]
     (subscribe react #(raise-event! newes (:event %)) [newes])
     newes)))


;; combinators for event sources

(defn hold
  "Creates a new signal that stores the last event as value."
  ([evtsource]
     (hold nil evtsource))
  ([initial-value evtsource]
     (let [newsig (signal :hold initial-value)]
       (subscribe evtsource #(setv! newsig (:event %)) [newsig])
       newsig)))


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
   (hold sig-or-val)
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


(defn delay
  "Creates an event source that receives occurences delayed by
   msecs milliseconds from the given event source."
  [msecs evtsource]
  (->> evtsource (pass (x/delayed-executor msecs))))


(defn calm
  "Creates an event source that receives occurences delayed by
   msecs milliseconds from the given event source. When a subsequent
   event arrives before a current event is propagated the current event
   is omitted and the delay starts from the beginning for the
   new event."
  [msecs evtsource]
  (->> evtsource (pass (x/calmed-executor msecs))))


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
  ([f initial-value evtsource]
     (let [newsig (signal :reduce initial-value)]
       (subscribe evtsource #(setv! newsig (f (getv newsig) (:event %))) [newsig])
       newsig)))


(defn snapshot
  "Creates a signal that takes the value of the signal sig whenever
   the given event source raises an event."
  ([sig evtsource]
     (snapshot sig nil evtsource))
  ([sig initial-value evtsource]
  (let [newsig (signal :snapshot initial-value)]
    (subscribe evtsource (fn [_] (setv! newsig (getv sig))) [newsig])
    newsig)))


(defn react-with
  "Subscribes f as listener to the event source and
   returns the event source. The function f receives
   the occurence as argument, any return value is discarded."
  [f evtsource]
  (subscribe evtsource f []))


;; combinators for signals


(defn changes
  "Creates an event source from a signal so that an event is raised
   whenever the signal value changes. If the fn-or-val argument
   evaluates to a function, then it is applied to the signals
   new value. An event is raised when the function returns a non-nil
   result. If fn-or-val is not a function it is assumed to be the
   event that will be raised on signal value change."
  ([sig]
     (changes identity sig))
  ([fn-or-val sig]
  (let [newes (eventsource :changes)]
    (subscribe sig
               (fn [new]
                 (if-let [evt (if (fn? fn-or-val)
                                (fn-or-val new)
                                fn-or-val)]
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


(defn- as-vector
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
        listener-fn (if (= 1 (count output-sigs))
                      (fn [_] (setv! (first output-sigs) (calc-outputs)))
                      (fn [_] (some->> (calc-outputs)
                                       as-vector
                                       (setvs! output-sigs))))]
    (doseq [sig input-sigs]
      (subscribe sig listener-fn output-sigs))
    (listener-fn nil)) ; initial value sync
  output-sigs)


(defn lift*
  "Creates a signal that is updated by applying the n-ary function
   f to the values of the input signals whenever one value changes."
  [f & sigs]
  (let [newsig (signal :lift 0)]
    (bind! f (vec (clojure.core/map as-signal sigs)) [newsig])
    newsig))


(defn if*
  "Creates a signal that contains the value of t-sig if cond-sig contains
   true, otherwise the value of f-sig."
  [cond-sig t-sig f-sig]
  (let [newsig (signal :if nil)
        switch-fn (fn [b] (setv! newsig (if b (getv t-sig) (getv f-sig))))
        propagate-fn (fn [x] (setv! newsig (if (getv cond-sig) (getv t-sig) (getv f-sig))))]
    (subscribe cond-sig switch-fn [newsig])
    (subscribe t-sig propagate-fn [newsig])
    (subscribe f-sig propagate-fn [newsig])
    (switch-fn (getv cond-sig))
    newsig))


(defn and*
  "Creates a signal that contains the result of a logical And of all signals values."
  [& sigs]
  (apply (partial lift* (fn [& xs]
                          (if (every? identity xs)
                            (last xs)
                            false)))
         sigs))


(defn or*
  "Creates a signal that contains the result of a logical Or of all signals values."
  [& sigs]
  (apply (partial lift* (fn [& xs]
                   (if-let [r (some identity xs)]
                     r
                     false)))
         sigs))


(defn- lift-exprs
  [exprs]
  (clojure.core/map #(list 'reactor.core/lift %) exprs))


(defmacro lift
  "Marco that takes an expr, lifts it (and all subexpressions) and
   returns a signal that changes whenever a value of the signals of the
   sexpr changes.
   Supports in addition to application of regular functions the following
   subset of Clojure forms:
      if, or, and, let"
  [expr]
  (if (list? expr)
    (case (first expr)
      let (let [[_ bindings & exprs] expr
                lifted-bindings (->> bindings
                                     (partition 2)
                                     (mapcat (fn [[s expr]] [s (list 'reactor.core/lift expr)]))
                                     vec)]
            `(let ~lifted-bindings ~@(lift-exprs exprs)))
      if (let [[_ c t f] expr]
            `(if* (lift ~c)
                  (lift ~t)
                  ~(if f `(lift ~f) `(lift nil))))
      or `(or* ~@(lift-exprs (rest expr)))
      and `(and* ~@(lift-exprs (rest expr)))
      `(lift* ~(first expr) ~@(lift-exprs (rest expr)))) ; regular function application
    `(as-signal ~expr))) ;; no list, make sure it's a signal


(defn process-with
  "Connects a n-ary function to n input signals so that the function is
   executed whenever one of the signals changes its value. The output of the
   function execution is discarded. Instead returns the input signals."
  [f & input-sigs]
  (bind! f (vec input-sigs) nil)
  (if (= 1 (count input-sigs)) (first input-sigs) (vec input-sigs)))


(defn stop-timer
  "Stops the time signal from being updated."
  [tsig]
  (-> tsig :executor x/cancel)
  (swap! timer-signals #(disj % tsig)))


(defn stop-all-timers
  "Stops all time signals at once."
  []
  (doseq [t @timer-signals]
    (-> t :executor x/cancel))
  (reset! timer-signals #{}))


