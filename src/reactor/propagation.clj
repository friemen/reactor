(ns reactor.propagation)

;; Supports the implementation of Event Sources and Signals.
;; A Propagator is a one argument function together with a set of targets,
;; that the function affects (usually by changing them somehow).

(defn propagator
  "Creates and returns a new propagator."
  [f ts]
  {:fn f :targets (set ts)})

(defprotocol PropagatorSet
  (add! [this prop]
    "Add propagator.")
  (remove! [this prop]
    "Remove propagator.")
  (propagate-all! [this x]
    "Invokes the propagator function for every added propagator, passing x as only argument.")
  (propagators [this]
    "Returns the set of added propagators."))


(deftype DefaultPropagatorSet [ps]
  PropagatorSet
  (add! [this p]
    {:pre [(fn? (:fn p))]}
    (swap! ps #(conj % p)))
  (remove! [this p]
    {:pre [(fn? (:fn p))]}
    (swap! ps #(disj % p)))
  (propagate-all! [this x]
    (doseq [p @ps] ((:fn p) x)))
  (propagators [this]
    @ps))

(defn propagator-set
  "Creates and returns a new PropagatorSet instance."
  []
  (DefaultPropagatorSet. (atom #{})))


