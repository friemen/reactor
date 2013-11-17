(ns reactor.propagation
  "Support for propagation of arbitrary values through a graph of propagators.")

;; Supports the implementation of Event Sources and Signals.
;; A Propagator is a one argument function together with a set of targets,
;; that the function affects (usually by changing them somehow).

(defn propagator
  "Creates and returns a new propagator."
  [f target]
  {:fn f :target target})

(defprotocol PropagatorSet
  (add! [pset prop]
    "Add propagator.")
  (remove! [pset prop]
    "Remove propagator.")
  (propagate-all! [pset x]
    "Invokes the propagator function for every added propagator, passing x as only argument.")
  (propagators [pset]
    "Returns the set of added propagators."))


(defrecord DefaultPropagatorSet [ps]
  PropagatorSet
  (add! [pset p]
    {:pre [(fn? (:fn p))]}
    (swap! ps #(conj % p)))
  (remove! [pset p]
    {:pre [(fn? (:fn p))]}
    (swap! ps #(disj % p)))
  (propagate-all! [pset x]
    (doseq [p @ps] ((:fn p) x)))
  (propagators [pset]
    @ps))

(defn propagator-set
  "Creates and returns a new PropagatorSet instance."
  []
  (DefaultPropagatorSet. (atom #{})))

