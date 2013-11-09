(ns reactor.core
  "Factories and combinators for FRP style signals and event sources."
  (:refer-clojure :exclude [delay filter merge map reduce time apply])
  (:require [reactor.propagation :as p]
            [reactor.execution :as x]
            [clojure.core :as c]))

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



;; types

(defrecord Occurence [event timestamp])

(defprotocol Reactive
  (subscribe [react f followers]
    "Subscribes a one-argument listener function. The followers seq contains reactives
     that are affected by side-effects that the listener function has.
     In case of an event source the listener fn is invoked with an Occurence instance.
     In case of a signal the listener fn is invoked with the a pair [old-value new-value]
     of the signal.")
  (unsubscribe [react f]
    "Removes the listener fn from the list of followers.")
  (followers [react]
    "Returns reactives that follow this reactive.")
  (role [react]
    "Returns a keyword denoting the functional role of the reactive."))


(defprotocol Signal
  (getv [sig]
    "Returns the current value of this signal.")
  (setv! [sig value]
    "Sets the value of this signal and returns the value.")
  (last-updated [sig]
    "Returns the absolute timestamp of the last setv!"))


(defprotocol EventSource
  (raise-event! [evtsource evt]
    "Sends a new event."))


(declare signal lagged-signal time elapsed-time eventsource)

;; time utilities

(defn now
  []
  "Returns System/currentTimeMillis."
  (System/currentTimeMillis))


(defonce ^:private timer-signals (atom #{}))

(defn start-timer
  "Starts the timer to update the given time signal."
  [tsig]
  (when-not (@timer-signals tsig)
    (let [update-fn #(reset! (:time-atom tsig) (now))]
      (update-fn)
      (x/schedule (:executor tsig) update-fn)
      (swap! timer-signals #(conj % tsig)))))


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




;; common functions for reactives (signals and event sources)

(defn pass
  "Creates a reactive of the same type as the given reactive.
   Uses the specified executor to handle the event or value propagation in a different thread.
   See also protocol Executor in ns reactor.execution."
  [executor react]
  (cond
   (satisfies? Signal react)
   (let [newsig (signal :signalpass executor nil)]
     (subscribe react (fn [[old new]] (setv! newsig new)) [newsig])
     newsig)
   (satisfies? EventSource react)
   (let [newes (eventsource :eventpass executor)]
     (subscribe react #(raise-event! newes %) [newes])
     newes)))


(defn react-with
  "Subscribes f as listener to the reactive and returns it.
   In case of an event source the function f receives the occurence as argument.
   In case of a signal the function f receives a pair [old-value new-value] as argument.
   Any return value of f is discarded."
  [f react]
  (subscribe react f []))



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
                 (Occurence. (if (fn? transform-fn-or-value)
                               (transform-fn-or-value (:event %))
                               transform-fn-or-value)
                             (:timestamp %)))
               [newes])
    newes))


(defn filter
  "Creates a new event source that only raises an event
   when the predicate returns true for the original event."
  [pred evtsource]
  (let [newes (eventsource :filter)]
    (subscribe evtsource #(when (pred (:event %))
                              (raise-event! newes %))
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
      (subscribe es #(raise-event! newes %) [newes]))
    newes))


(defn switch
  "Creates a signal that initially follows the given signal sig.
   Upon any occurence of the given event source the signal switches to
   follow the signal that the occurence contained."
  [sig-or-value evtsource]
  (let [sig (as-signal sig-or-value)
        newsig (signal :switch (getv sig))
        sig-listener (fn [[old new]] (setv! newsig new))
        switcher (fn [{timestamp :timestamp evtsig :event}]
                   {:pre [(instance? reactor.core.Signal evtsig)]}
                   (unsubscribe sig sig-listener)
                   (subscribe evtsig sig-listener [newsig])
                   (setv! newsig (getv evtsig)))]
    (subscribe sig sig-listener [newsig])
    (subscribe evtsource switcher [newsig])
    newsig))


(defn reduce-t
  "Creates a signal from an event source. On each event
   the given 2-arg function is invoked with the current signals
   value as first and the occurence as second parameter.
   The result of the function is set as new value of the signal."
  ([f evtsource]
     (reduce-t f nil))
  ([f initial-value evtsource]
     (let [newsig (signal :reduce-t initial-value)]
       (subscribe evtsource
                  #(setv! newsig (f (getv newsig)
                                    (:event %)
                                    (- (:timestamp %)
                                       (or (last-updated newsig) (:timestamp %)))))
                  [newsig])
       newsig)))


(defn reduce
  "Creates a signal from an event source. On each event
   the given 2-arg function is invoked with the current signals
   value as first and the event as second parameter.
   The result of the function is set as new value of the signal."
  ([f evtsource]
     (reduce f nil))
  ([f initial-value evtsource]
     (let [newsig (signal :reduce initial-value)]
       (subscribe evtsource #(setv! newsig (f (getv newsig) (:event %))) [newsig])
       newsig)))


(defn snapshot
  "Creates an event source that takes the value of the signal sig whenever
   the given event source raises an event."
  [sig evtsource]
  (let [newes (eventsource :snapshot)]
    (subscribe evtsource (fn [_] (raise-event! newes (getv sig))) [newes])
    newes))



;; combinators for signals


(defn behind
  "Creates a signal from an existing signal that reflects the values
   with the specified lag."
  ([sig]
     (behind 1 sig))
  ([lag sig]
     (let [newsig (lagged-signal lag (getv sig))]
       (subscribe sig (fn [[old new]] (setv! newsig new)) [newsig])
       newsig)))


(defn changes
  "Creates an event source from a signal so that an event is raised
   whenever the signal value changes. The event is a pair [old-value new-value].
   If the fn-or-val argument
   evaluates to a function, then it is applied to the signals
   new value. An event is raised when the function returns a non-nil
   result. If fn-or-val is not a function it is assumed to be the
   event that will be raised on signal value change."
  ([sig]
     (changes identity sig))
  ([f sig]
  (let [newes (eventsource :changes)]
    (subscribe sig (fn [[old new]] (raise-event! newes [(f old) (f new)])) [newes])
    newes)))


(defn setvs!
  "Sets each output-signal to the respective value."
  [output-signals values]
  (doseq [sv (c/map vector output-signals values)]
    (setv! (first sv) (second sv))))


;; binding of signals, lifting of functions and expressions


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
  (let [calc-outputs (fn []
                       (let [input-values (c/map getv input-sigs)
                             output-values (c/apply f input-values)]
                         output-values))
        listener-fn (if (= 1 (count output-sigs))
                      (fn [_] (setv! (first output-sigs) (calc-outputs)))
                      (fn [_] (some->> (calc-outputs)
                                       as-vector
                                       (setvs! output-sigs))))]
    (doseq [sig input-sigs :when (nil? (:no-subscribe sig))]
      (subscribe sig listener-fn output-sigs))
    (listener-fn nil)) ; initial value sync
  output-sigs)


(defn bind-one!
  [f newsig & sigs]
  (bind! f (vec (c/map as-signal sigs)) [newsig])
  newsig)


(defn apply
  "Creates a signal that is updated by applying the n-ary function
   f to the values of the input signals whenever one value changes."
  [f & sigs]
  (let [newsig (signal :apply 0)]
    (bind! f (c/map as-signal sigs) [newsig])
    newsig))


(defn if*
  "Creates a signal that contains the value of t-sig if cond-sig contains
   true, otherwise the value of f-sig."
  [cond-sig t-sig f-sig]
  (let [newsig (signal :if nil)
        switch-fn (fn [[old new]] (setv! newsig (if new (getv t-sig) (getv f-sig))))
        propagate-fn (fn [_] (setv! newsig (if (getv cond-sig) (getv t-sig) (getv f-sig))))]
    (subscribe cond-sig switch-fn [newsig])
    (subscribe t-sig propagate-fn [newsig])
    (subscribe f-sig propagate-fn [newsig])
    (switch-fn [nil (getv cond-sig)])
    newsig))


(defn and*
  "Creates a signal that contains the result of a logical And of all signals values."
  [& sigs]
  (c/apply
   (partial apply (fn [& xs]
                    (if (every? identity xs)
                      (last xs)
                      false)))
   sigs))


(defn or*
  "Creates a signal that contains the result of a logical Or of all signals values."
  [& sigs]
  (c/apply
   (partial apply (fn [& xs]
                    (if-let [r (some identity xs)]
                      r
                      false)))
   sigs))


(declare lift-expr)

(defn- lift-exprs
  [exprs]
  (c/map lift-expr exprs))

;; TODO add support for fn, -> ->>

(defn- lift-expr
  [expr]
     (if (list? expr)
       (case (first expr)
         let (let [[_ bindings & exprs] expr
                   lifted-bindings (->> bindings
                                        (partition 2)
                                        (mapcat (fn [[s expr]] [s (lift-expr expr)]))
                                        vec)]
               `(let ~lifted-bindings ~@(lift-exprs exprs)))
         ;; TODO fn (fn fn*) (let [[_ p & exprs] expr] `(signal (fn ~p )))
         if (let [[_ c t f] expr]
              `(if* ~(lift-expr c)
                    ~(lift-expr t)
                    ~(if f (lift-expr f) (lift-expr nil))))
         or `(or* ~@(lift-exprs (rest expr)))
         and `(and* ~@(lift-exprs (rest expr)))
         `(reactor.core/apply ~(first expr) ~@(lift-exprs (rest expr)))) ; regular function application
       (case expr
         <S> (symbol "<S>")
         `(as-signal ~expr))))  ;; no list, make sure it's a signal


(defmacro lift
  "Macro that takes an expr, lifts it (and all subexpressions) and
   returns a signal that changes whenever a value of the signals of the
   sexpr changes.
   Supports in addition to application of regular functions the following
   subset of Clojure forms:
      if, or, and, let"
  ([expr]
     `(lift 0 nil ~expr))
  ([initial-value expr]
     `(lift initial-value nil ~expr))
  ([initial-value tsig expr]
     (let [s (symbol "<S>")
           t (symbol "<T>")]
       `(let [~s (assoc (reactor.core/signal ~initial-value) :no-subscribe true)
              ~@(if tsig `(~t (reactor.core/elapsed-time ~s ~tsig)))]
          (bind-one! identity ~s ~(lift-expr expr))
          ~s)))) 



(defn process-with
  "Connects a n-ary function to n input signals so that the function is
   executed whenever one of the signals changes its value. The output of the
   function execution is discarded. Instead returns the input signals."
  [f & input-sigs]
  (bind! f (vec input-sigs) nil)
  (if (= 1 (count input-sigs)) (first input-sigs) (vec input-sigs)))



;; default implementations and factories

(def ^:private reactive-fns
  {:subscribe (fn [r f followers]
                (p/add! (.ps r) (p/propagator f followers))
                r)
   :unsubscribe (fn [r f]
                  (doseq [p (->> (.ps r)
                                 p/propagators
                                 (c/filter #(or (nil? f) (= (:fn %) f))))]
                    (p/remove! (.ps r) p))
                  r)
   :followers (fn [r]
                (mapcat :targets (p/propagators (.ps r))))
   :role (fn [r] (.role r))})



(defrecord DefaultSignal [role value-atom updated-atom ps]
  Signal
  (getv [_]
    @value-atom)
  (setv! [_ value]
    (reset! updated-atom (now))
    (reset! value-atom value))
  (last-updated [sig]
    @updated-atom))

(extend DefaultSignal Reactive reactive-fns)


(defrecord LaggedSignal [role lag value-ref q-ref ps]
  Signal
  (getv [_]
    (first @value-ref))
  (setv! [_ value]
    (dosync
     (let [vs (conj @q-ref [value (now)])]
       (if (> (count vs) lag)
        (let [[v & rest] vs]
          (ref-set value-ref v)
          (ref-set q-ref (vec rest)))
        (ref-set q-ref vs))
       value)))
  (last-updated [sig]
    (second @value-ref)))

(extend LaggedSignal Reactive reactive-fns)


(defrecord Time [role time-atom executor ps]
  Signal
  (getv [_]
    @time-atom)
  (setv! [_ value]
    (throw (UnsupportedOperationException. "A time signal cannot be set")))
  (last-updated [_]
    @time-atom))

(extend Time Reactive reactive-fns)


;; propagate elapsed time between last update of signal and current
;; time change
;; provide elapsed time between last update of signal and now

(defrecord ElapsedTime [role sig ps]
  Signal
  (getv [_]
    (- (now) (last-updated sig)))
  (setv! [_ value]
    (throw (UnsupportedOperationException. "A time signal cannot be set")))
  (last-updated [_]
    (now)))

(extend ElapsedTime Reactive reactive-fns)


(defn- as-occ
  [evt-or-occ]
  (if (instance? Occurence evt-or-occ)
    evt-or-occ
    (Occurence. evt-or-occ (now))))


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
           updated (atom (if initial-value (now) nil))
           ps (p/propagator-set)]
       (add-watch a :signal (fn [ctx key old new]
                              (if (not= old new)
                                (x/schedule executor #(p/propagate-all! ps [old new])))))
       (DefaultSignal. role a updated ps))))


(defn lagged-signal
  "Creates a new lagged signal that reflect values with a lag.
   A lag of 0 means the value that was setv! is directly available via getv."
  ([lag initial-value]
     (lagged-signal :signal x/current-thread lag initial-value))
  ([role lag initial-value]
     (lagged-signal role x/current-thread lag initial-value))
  ([role executor lag initial-value]
     (let [r (ref (if initial-value [initial-value (now)] nil))
           q-ref (ref (if initial-value
                        (->> initial-value
                             (repeat lag)
                             (c/map #(vector % (now)))
                             vec)
                        []))
           ps (p/propagator-set)]
       (add-watch r :signal (fn [ctx key old new]
                              (if (not= old new)
                                (x/schedule executor #(p/propagate-all! ps [(first old) (first new)])))))
       (LaggedSignal. role lag r q-ref ps))))


(defn elapsed-time
  "Returns a new signal that keeps the elapsed time of the last update of
   the given signal, and propagates the elapsed time on every change of the
   given time signal."
  ([sig tsig]
     (elapsed-time :signal sig tsig))
  ([role sig tsig]
     (let [ps (p/propagator-set)
           newsig (ElapsedTime. role sig ps)]
       (subscribe tsig
                  (fn [[t-1 t]]
                    (p/propagate-all! ps [(- t-1 (last-updated sig))
                                          (-  t (last-updated sig))]))
                  [newsig])
       newsig)))


(defn time
  "Creates time signal that updates its value every resolution ms."
  [resolution]
  (let [a (atom (now))
        executor (x/timer-executor resolution)
        ps (p/propagator-set)
        newsig (Time. :time a executor ps)]
    (add-watch a :signal (fn [ctx key old new]
                           (if (not= old new)
                             (p/propagate-all! ps [old new]))))
    (start-timer newsig)
    newsig))


(defn eventsource
  "Creates a new event source."
  ([]
     (eventsource :eventsource x/current-thread))
  ([role]
     (eventsource role x/current-thread))
  ([role executor]
     (DefaultEventSource. role (p/propagator-set) executor)))


;; draft implementation for external propagation


(defn heights
  "Returns a map {reactive->height} for all reactives
   that are reachable by the given seq of reactives.
   Links between reactives that create cycles are omitted, so the
   graph of reachable reactives is treated as a tree.
   The 'height' of a reactive denotes the length of the longest
   path to a leaf of this tree. A leaf has height 0.
   'Reactive t follows reactive s' implicates 'height s > height t'"
  ([reactives]
     (heights reactives {}))
  ([reactives rhm]
     (loop [rs reactives, m rhm]
       (if-let [r (first rs)]
         (if (m r)
           (recur (rest rs) m)
           (let [fs (followers r)]
             (recur (rest rs) (if (empty? fs)
                                (assoc m r 0)
                                (let [frhm (heights fs (assoc m r -1))]
                                  (->> fs
                                       (c/map frhm)
                                       (c/apply max)
                                       inc
                                       (assoc m r)
                                       (c/merge frhm)))))))
         m))))




