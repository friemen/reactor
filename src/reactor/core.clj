(ns reactor.core
  "Factories and combinators for FRP style signals and event sources."
  (:refer-clojure :exclude [apply delay filter merge map reduce remove time])
  (:require [reactor.propagation :as p]
            [reactor.execution :as x]
            [clojure.core :as c]
            [clojure.set]
            [clojure.data.priority-map :refer [priority-map priority-map-by]]))

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


;; -----------------------------------------------------------------------------
;; types

(defrecord Occurence [event timestamp])

(defprotocol Reactive
  (subscribe [react follower f]
    "Subscribes a one-argument listener function. The follower is a reactive
     that is affected by side-effects that the listener function has.
     In case of an event source the listener fn is invoked with an Occurence instance.
     In case of a signal the listener fn is invoked with the a pair [old-value new-value]
     of the signal.")
  (unsubscribe [react follower]
    "Removes the follower and its listener fn from the list of followers.")
  (followers [react]
    "Returns reactives that follow this reactive.")
  (role [react]
    "Returns a keyword denoting the functional role of the reactive.")
  (publish! [react x]
    "Takes over the given value/occurrence x and publishes it to all followers.
     An event source will propagate the Occurence instance,
     a signal will propagate a pair [old-value new-value]."))


(defprotocol Signal
  (getv [sig]
    "Returns the current value of this signal.")
  (last-updated [sig]
    "Returns the absolute timestamp of the last publish!"))


(defprotocol EventSource
  (raise-event! [evtsource evt]
    "Sends a new event."))


;; -----------------------------------------------------------------------------

(declare signal lagged-signal time elapsed-time exceptions eventsource register! setv! enqueue!)


;; -----------------------------------------------------------------------------
;; time utilities

(defn now
  []
  "Returns System/currentTimeMillis."
  (System/currentTimeMillis))


;; -----------------------------------------------------------------------------
;; common functions for reactives (signals and event sources)

(defn pass
  "Creates a reactive of the same type as the given reactive.
   Uses the specified executor to handle the event or value propagation in a different thread.
   See also protocol Executor in ns reactor.execution."
  [executor react]
  (cond
   (satisfies? Signal react)
   (let [newsig (signal :signalpass executor nil)]
     (subscribe react newsig (fn [[old new]] (setv! newsig new)))
     newsig)
   (satisfies? EventSource react)
   (let [newes (eventsource :eventpass executor)]
     (subscribe react newes #(raise-event! newes %))
     newes)))


(defn react-with
  "Subscribes f as listener to the reactive and returns it.
   In case of an event source the function f receives the occurence as argument.
   In case of a signal the function f receives a pair [old-value new-value] as argument.
   Any return value of f is discarded."
  [f react]
  (subscribe react nil f))

(declare hold)

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
   :else (signal :constantly sig-or-val)))


;; -----------------------------------------------------------------------------
;; combinators for event sources

(defn hold
  "Creates a new signal that stores the last event as value."
  ([evtsource]
     (hold nil evtsource))
  ([initial-value evtsource]
     (let [newsig (signal :hold initial-value)]
       (subscribe evtsource newsig #(setv! newsig (:event %)))
       newsig)))


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
               newes
               #(raise-event!
                 newes
                 (Occurence. (if (fn? transform-fn-or-value)
                               (transform-fn-or-value (:event %))
                               transform-fn-or-value)
                             (:timestamp %))))
    newes))


(defn filter
  "Creates a new event source that only raises an event
   when the predicate returns true for the original event."
  [pred evtsource]
  (let [newes (eventsource :filter)]
    (subscribe evtsource newes #(when (pred (:event %))
                                  (raise-event! newes %)))
    newes))


(defn remove
  "Creates a new event source that suppresses forwarding of
   an event when the predicate returns true for the original event."
  [pred evtsource]
  (filter (complement pred) evtsource))


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
      (subscribe es newes #(raise-event! newes %)))
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
                   (unsubscribe sig newsig)
                   (subscribe evtsig newsig sig-listener)
                   (setv! newsig (getv evtsig)))]
    (subscribe sig newsig sig-listener)
    (subscribe evtsource newsig switcher)
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
                  newsig
                  #(setv! newsig (f (getv newsig)
                                    (:event %)
                                    (- (:timestamp %)
                                       (or (last-updated newsig) (:timestamp %))))))
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
       (subscribe evtsource newsig #(setv! newsig (f (getv newsig) (:event %))))
       newsig)))


(defn snapshot
  "Creates an event source that takes the value of the signal sig whenever
   the given event source raises an event."
  [sig evtsource]
  (let [newes (eventsource :snapshot)]
    (subscribe evtsource newes (fn [_] (raise-event! newes (getv sig))))
    newes))



;; -----------------------------------------------------------------------------
;; combinators for signals


(defn behind
  "Creates a signal from an existing signal that reflects the values
   with the specified lag."
  ([sig]
     (behind 1 sig))
  ([lag sig]
     (let [newsig (lagged-signal lag (getv sig))]
       (subscribe sig newsig (fn [[old new]] (setv! newsig new)))
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
    (subscribe sig newes (fn [[old new]] (raise-event! newes [(f old) (f new)])))
    newes)))


(defn setv!
  "Sets the value of this signal and returns the value."
  [sig value]
  (enqueue! sig value)
  value)


(defn setvs!
  "Sets each output-signal to the respective value."
  [output-signals values]
  (doseq [sv (c/map vector output-signals values)]
    (setv! (first sv) (second sv))))


(defn as-val
  "Returns x if x is not a signal, otherwise returns (getv x)"
  [x]
  (if (satisfies? Signal) (getv x) x))

;; -----------------------------------------------------------------------------
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
  "Connects n input-signals with one output-signal so that on
   each change of an input signal value the value of the output signal
   is re-calculated by the function f. Function f must accept n
   arguments and must a single value."
  [f input-sigs output-sig]
  (let [calc-outputs (fn []
                       (let [input-values (c/map getv input-sigs)
                             output-value (c/apply f input-values)]
                         #_(println input-values "->" output-value)
                         output-value))
        listener-fn (fn [_]
                      (let [v (calc-outputs)]
                        (when output-sig
                              (setv! output-sig v))))]
    (doseq [sig input-sigs]
      (subscribe sig output-sig listener-fn))
    ; initial value sync
    (let [v (calc-outputs)]
      (when output-sig
        (publish! output-sig v)))
  output-sig))


; TODO eliminate this (replace by bind!)
(defn bind-one!
  [f newsig & sigs]
  (bind! f (vec (c/map as-signal sigs)) newsig))


(defn apply
  "Creates a signal that is updated by applying the n-ary function
   f to the values of the input signals whenever one value changes."
  [f sigs]
  (let [newsig (signal :apply 0)]
    (bind! f (c/map as-signal sigs) newsig)
    newsig))


(defn if*
  "Creates a signal that contains the value of t-sig if cond-sig contains
   true, otherwise the value of f-sig."
  [cond-sig t-sig f-sig]
  (let [newsig (signal :if nil)
        switch-fn (fn [[old new]] (setv! newsig (if new (getv t-sig) (getv f-sig))))
        propagate-fn (fn [_] (setv! newsig (if (getv cond-sig) (getv t-sig) (getv f-sig))))]
    (subscribe cond-sig newsig switch-fn)
    (subscribe t-sig newsig propagate-fn)
    (subscribe f-sig newsig propagate-fn)
    ; initial value sync
    (publish! newsig (if (getv cond-sig) (getv t-sig) (getv f-sig)))
    newsig))


(defn and*
  "Creates a signal that contains the result of a logical And of all signals values."
  [& sigs]
  (apply (fn [& xs]
            (if (every? identity xs)
              (last xs)
              false)) sigs))


(defn or*
  "Creates a signal that contains the result of a logical Or of all signals values."
  [& sigs]
  (apply (fn [& xs]
                    (if-let [r (some identity xs)]
                      r
                      false)) sigs))


(declare lift-expr)

(defn- lift-exprs
  [exprs]
  (vec (c/map lift-expr exprs)))

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
         `(reactor.core/apply ~(first expr) ~(lift-exprs (rest expr)))) ; regular function application
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
     `(lift 0 ~expr))
  ([initial-value expr]
     (let [s (symbol "<S>")]
       `(let [~s (reactor.core/signal ~initial-value)]
          (bind-one! identity ~s ~(lift-expr expr))
          ~s)))) 



(defn process-with
  "Connects a n-ary function to n input signals so that the function is
   executed whenever one of the signals changes its value. The output of the
   function execution is discarded. Instead returns the input signals."
  [f & input-sigs]
  (bind! f (vec input-sigs) nil)
  (if (= 1 (count input-sigs)) (first input-sigs) (vec input-sigs)))



;; -----------------------------------------------------------------------------
;; default implementations and factories

(def ^:private reactive-fns
  {:subscribe (fn [r follower f]
                (p/add! (.ps r) (p/propagator f follower))
                r)
   :unsubscribe (fn [r follower]
                  (doseq [p (->> (.ps r)
                                 p/propagators
                                 (c/filter #(or (nil? follower) (= (:target %) follower))))]
                    (p/remove! (.ps r) p))
                  r)
   :followers (fn [r]
                (->> r (.ps) p/propagators (c/map :target) (c/remove nil?))
                #_(c/map :target (p/propagators (.ps r))))
   :role (fn [r] (.role r))})



(defrecord DefaultSignal [role value-atom updated-atom executor ps]
  Signal
  (getv [_]
    @value-atom)
  (last-updated [sig]
    @updated-atom))

(extend DefaultSignal
  Reactive
  (-> reactive-fns
      (assoc :publish! (fn [sig value]
                         (let [{updated-atom :updated-atom
                                value-atom :value-atom
                                ps :ps
                                executor :executor} sig
                               new value
                               old @value-atom]
                           (reset! updated-atom (now))
                           (reset! value-atom value)
                           (if (not= old new)
                             (x/schedule executor #(p/propagate-all! ps [old new]))))))))


(defrecord LaggedSignal [role lag value-ref q-ref executor ps]
  Signal
  (getv [_]
    (first @value-ref))
  (last-updated [sig]
    (second @value-ref)))

(extend LaggedSignal
  Reactive
  (-> reactive-fns
      (assoc :publish! (fn [sig value]
                         (dosync
                          (let [{lag :lag
                                 value-ref :value-ref
                                 q-ref :q-ref
                                 ps :ps
                                 executor :executor} sig
                                 vs (conj @q-ref [value (now)])]
                            (if (> (count vs) lag)
                              (let [[new & rest] vs
                                    old @value-ref]
                                (ref-set value-ref new)
                                (ref-set q-ref (vec rest))
                                (if (not= old new)
                                  (x/schedule executor #(p/propagate-all! ps [(first old) (first new)]))))
                              (ref-set q-ref vs))
                            value))))))


(defrecord Time [role time-atom ps]
  Signal
  (getv [_]
    @time-atom)
  (last-updated [_]
    @time-atom))

(extend Time
  Reactive
  (-> reactive-fns
      (assoc :publish! (fn [sig value]
                         (let [{time-atom :time-atom
                                ps :ps} sig
                                new value
                                old @time-atom]
                           (reset! time-atom value)
                           (if (not= old new)
                             (p/propagate-all! ps [old new])))))))


(defrecord ElapsedTime [role sig ps]
  Signal
  (getv [_]
    (- (now) (last-updated sig)))
  (last-updated [_]
    (now)))

(extend ElapsedTime
  Reactive
  (-> reactive-fns
      (assoc :publish! (fn [{ps :ps} value]
                         (p/propagate-all! ps value)))))


(defn- as-occ
  [evt-or-occ]
  (if (instance? Occurence evt-or-occ)
    evt-or-occ
    (Occurence. evt-or-occ (now))))


(defrecord DefaultEventSource [role executor ps]
  EventSource
  (raise-event! [evtsource evt]
    (enqueue! evtsource (as-occ evt))
    evt))

(extend DefaultEventSource
  Reactive
  (-> reactive-fns
      (assoc :publish! (fn [evtsource value]
                         (let [{executor :executor
                                ps :ps} evtsource]
                           (x/schedule executor #(p/propagate-all! ps (as-occ value))))))))


;; -----------------------------------------------------------------------------
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
           ps (p/propagator-set)
           newsig (DefaultSignal. role a updated executor ps)]
       (register! newsig)
       newsig)))


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
           ps (p/propagator-set)
           newsig (LaggedSignal. role lag r q-ref executor ps)]
       (register! newsig)
       newsig)))


(defn elapsed-time
  "Returns a new signal that keeps the elapsed time of the last update of
   the given signal, and propagates the elapsed time on every change of the
   given time signal."
  ([sig]
     (elapsed-time :signal sig))
  ([role sig]
     (let [ps (p/propagator-set)
           newsig (ElapsedTime. role sig ps)]
       (subscribe time
                  newsig
                  (fn [[t-1 t]]
                    #_(println (last-updated sig) (- t (last-updated sig)))
                    (publish! newsig [(- t-1 (last-updated sig))
                                      (-  t (last-updated sig))])))
       (register! newsig)
       newsig)))


(defn eventsource
  "Creates a new event source."
  ([]
     (eventsource :eventsource x/current-thread))
  ([role]
     (eventsource role x/current-thread))
  ([role executor]
     (let [newes (DefaultEventSource. role executor (p/propagator-set))]
       (register! newes)
       newes)))


;; -----------------------------------------------------------------------------
;; bookkeeping / disposal for reactives and default reactives

(defonce time (Time. :time (atom (now)) (p/propagator-set)))
(defonce etime (DefaultSignal. :elapsed-time (atom 0) (atom (now)) x/current-thread (p/propagator-set)))
(defonce exceptions (let [ex (DefaultEventSource. :exceptions x/current-thread (p/propagator-set))]
                      (->> ex (react-with #(println %)))
                      ex))


(defonce default-reactives {:active #{time etime exceptions}
                            :disposed #{}})

(defonce reactives (atom default-reactives))

(defn register!
  [react]
  (swap! reactives #(update-in % [:active] conj react)))


(defn dispose!
  "Marks the given reactive as disposed. The next call to unlink! will
   remove the reactive from all other reactives that it follows."
  [react]
  (when-not ((:active default-reactives) react)
    (swap! reactives #(let [{as :active
                             ds :disposed} %]
                        {:active (disj as react)
                         :disposed (conj ds react)}))))

(defn disposed?
  "Determines if a reactive is active."
  [react]
  (nil? ((:active @reactives) react)))



(defn unlink!
  "Unsubscribes all followers marked as disposed from reactives
   that propagate to the disposed reactives.
   Event sources that don't have any followers left will also
   be marked as disposed."
  []
  (swap! reactives #(let [{ds :disposed
                           as :active} %
                          new-ds (->> (for [r as, f (->> r followers (c/filter ds))] 
                                        (do (unsubscribe r f)
                                            (if (and (= 0 (count (followers r)))
                                                     (satisfies? EventSource r))
                                              r
                                              nil)))
                                      (c/remove nil?)
                                      set)]
                      {:active (clojure.set/difference as new-ds)
                       :disposed new-ds})))


(defn reset-reactives!
  []
  (swap! reactives #(let [{ds :disposed
                           as :active} %]
                      (assoc default-reactives
                        :disposed (clojure.set/union ds
                                                     (clojure.set/difference as
                                                                             (:active default-reactives))))))
  (unlink!))



;; -----------------------------------------------------------------------------
;; implementation for propagation engine

(declare execute!)

(defonce engine (atom nil))
(def ^:dynamic auto-execute 10)
(defonce executor (atom nil))



(defn pr-reactive
  [react]
  (if react
    (if (satisfies? Signal react)
      (str "S" (role react) "(" (getv react) ")")
      (str "E" (role react)))
    nil))

(defn pr-reactives
  []
  (let [{as :active
         ds :disposed} @reactives]
    {:active (->> as (c/map pr-reactive))
     :disposed (->> ds (c/map pr-reactive))}))


(defn- pr-entry
  [ent]
  (update-in ent [:reactive] pr-reactive))

(defn pr-engine
  ([]
     (pr-engine @engine))
  ([eng]
     (-> eng
         (update-in [:pending-queue] #(->> % (c/map pr-entry) vec))
         (update-in [:execution-queue] #(->> % keys (c/map pr-entry) vec))
         (update-in [:heights] #(->> %
                                     (c/map (fn [[r h]]
                                              [(pr-reactive r) h]))
                                     (into {}))))))



(defn- before
  "Returns true, if e1 has to be processed before e2. The order of processing depends
   1. on the height: e1 is before e2 if height e1 > height e2
   2. on the order of arrival in the queue"
  [e1 e2]
  (if (= (:height e1) (:height e2))
    (< (:no e1) (:no e2))
    (> (:height e1) (:height e2))))



(defn reset-engine!
  "Removes all update entries from the engine and resets all counters."
  []
  (reset! engine {:next-no 0
                  :cycles 0
                  :pending-queue []
                  :execution-queue (priority-map-by (comparator before))
                  :heights {}
                  :execution-level Integer/MAX_VALUE}))

(reset-engine!)


(defn stop-engine!
  "Stops the timer executor which stops the scheduled engine execution."
  []
  (when @executor
       (x/cancel @executor)
       (reset! executor nil)))


(defn start-engine!
  "Start the engine for scheduled execution with the given resolution
   as delay between execution cycles.
   Sets var auto-execute to 0 which inhibits any implicit propagation
   after enqueue."
  ([]
     (start-engine! 50))
  ([resolution]
     (alter-var-root #'auto-execute (constantly 0))
     (stop-engine!)
     (when-not @executor
       (reset! executor (x/timer-executor resolution)))
     (x/cancel @executor)
     (x/schedule @executor execute!)
     (pr-engine @engine)))


(defn heights
  "Returns a map {reactive->height} for all reactives
   that are reachable by the given seq of reactives.
   Links between reactives that create cycles are omitted, so the
   graph of reachable reactives is treated as a tree.
   The 'height' of a reactive denotes the length of the longest
   path to a leaf of this tree. A leaf has height 0.
   'Reactive t follows reactive s' implicates 'height s > height t'"
  ([reacts]
     (heights reacts {}))
  ([reacts rhm]
     (loop [rs reacts, m rhm]
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


(defn enqueue
  "Add the value/occurence x for the reactive either to the execution-queue
   (if it is active, the reactive is reachable and 'downstream'
   compared to the current execution-level). Otherwise the entry is added to
   the pending-queue."
  [eng react x]
  #_(println "ENQUEUING" x "FOR" (pr-reactive react))
  (let [{no :next-no
         pq :pending-queue
         exq :execution-queue
         rhm :heights
         exl :execution-level} eng
         h (get rhm react)
        entry {:no no :height h :reactive react :value x}]
    (-> (if (or (empty? exq) (nil? h) (<= exl h))
          (assoc eng :pending-queue (conj pq entry))
          (assoc eng :execution-queue (conj exq [entry {:no no :height h}])))
        (assoc :next-no (inc no)))))


(defn begin
  "Takes all entries from pending-queue, calculates the height in
   the graph of reachable reactives and inserts them into the
   execution-queue."
  [eng]
  (let [es (:pending-queue eng)
        rhm (heights (c/map :reactive es))
        exq (->> es
                 (c/map #(assoc % :height (rhm (:reactive %))))
                 (c/map #(vector % %))
                 (into (:execution-queue eng)))
        exl (or (-> exq peek first :height) Integer/MAX_VALUE)]
    (-> eng
        (assoc :pending-queue []
               :execution-queue exq
               :heights rhm
               :execution-level exl))))


(defn update
  "Removes the first entry from the execution-queue and adapts
   the execution level."
  [eng]
  (let [exq (:execution-queue eng)
        exl (or (-> exq peek first :height) Integer/MAX_VALUE)]
    (assoc eng
      :execution-queue (if (empty? exq) exq (pop exq))
      :execution-level exl)))


(defn propagate!
  "Fills the execution-queue from the pending-queue.
   Repeats publish! for first entry of the execution-queue as long
   as the execution-queue is not empty."
  []
  (loop [eng (swap! engine begin)]
    (when-let [e (-> eng :execution-queue peek first)]
      (publish! (:reactive e) (:value e))
      (recur (swap! engine update)))))


(defn execute!
  "Publishes new time values and calls propagate!, handles errors
   by sending them to the exceptions event source."
  []
  (try (do (publish! etime (/ (- (now) (getv time)) 1000))
           (publish! time (now))
           (propagate!))
       (catch Exception ex (raise-event! exceptions ex)))  
  (pr-engine (swap! engine update-in [:cycles] inc)))


(defn enqueue!
  "Adds an update entry for the given reactive and value x
   to either the execution queue or the pending queue.
   The var auto-execute specifies the number of automatic
   propagations (useful for REPL usage and unit tests)."
  [react x]
  (let [stopped? (-> @engine :execution-queue empty?)]
    (swap! engine #(enqueue % react x))
    (when (and stopped? (nil? @executor))
      (doseq [_ (range auto-execute)]
        (propagate!)))))



