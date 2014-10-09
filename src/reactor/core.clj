(ns reactor.core
  "Factories and combinators for behaviors and eventstreams."
  (:refer-clojure :exclude [concat count delay distinct drop drop-while
                            drop-last filter into merge map mapcat reduce
                            remove some swap! take take-last take-while])
  (:require [clojure.core :as c]
            [clojure.string :as s]
            [reactnet.scheduler :as sched]
            [reactnet.reactives]
            [reactnet.executors]
            [reactnet.debug :as dbg]
            [reactnet.core :as rn
             :refer [add-links! broadcast-value completed? default-link-fn
                     enq enqueue-values execute fvalue link-inputs
                     link-outputs make-link make-network *netref* now on-error
                     reactive? remove-links! scheduler single-value values zip-values]
             :exclude [push! complete!]]
            [reactnet.netrefs :refer [agent-netref]])
  (:import [clojure.lang PersistentQueue]
           [reactnet.reactives Behavior Eventstream Seqstream Fnbehavior]
           [reactnet.executors FutureExecutor]))


;; TODOS
;; - Invent marker protocol to support behavior? and eventstream? regardless of impl class
;; - err-retry --> support "exponential backoff"
;; - allow reference to defined behavior in lift
;;   (by first letting and passing the resulting behavior to lift-* functions)


(alter-var-root #'reactnet.core/*netref*
                (fn [_]
                  (agent-netref (make-network "default" []))))


;; ---------------------------------------------------------------------------
;; Factories / Predicates

(defn behavior
  "Returns a new behavior. 
  It can have an optional label passed via the :label key."
  ([value]
     (behavior value :label (str (gensym "b"))))
  ([_ label]
     (behavior nil :label label))
  ([value _ label]
     (Behavior. label
                (atom [value (now)])
                (atom true)
                (atom true))))


(defn behavior?
  "Returns true if r is a behavior."
  [r]
  (or (instance? Behavior r)
      (instance? Fnbehavior r)))


(defn eventstream
  "Returns a new eventstream.
  It can have an optional label passed via the :label key."
  [& {:keys [label] :or {label (str (gensym "e"))}}]
  (Eventstream. label
                (atom {:queue (PersistentQueue/EMPTY)
                       :last-occ nil
                       :completed false})
                5000))


(defn eventstream?
  "Returns true if r is an eventstream."
  [r]
  (or (instance? Eventstream r)
      (instance? Seqstream r)))


;; ---------------------------------------------------------------------------
;; Push! Complete! Reset


(defn network
  "Creates a new network with an optional id. 
  If the id is omitted a gensym is used."
  ([]
     (network (str (gensym "network"))))
  ([id]
     (agent-netref
      (make-network id []))))


(defmacro with
  "Changes the dynamic binding of var reactnet.core/*netref* to the
  given network n."
  [n & exprs]
  `(binding [reactnet.core/*netref* ~n]
     ~@exprs))


(defmacro setup-network
  "Takes an id and a vector of symbol-expr bindings, creates a new
  network, puts the binding pairs in a let and returns a map that
  contains the symbols as keys and the values of the expressions as
  values.  The expressions are evaluated in a r/with (i.e. the
  new network is dynamically bound to reactnet.core/*netref*).  
  An additional :netref key in the resulting map points to the new network."
  [id & let-pairs]
  `(with
     (network ~id)
     (let [~@let-pairs]
       ~(let [let-pairs# (partition 2 let-pairs)]
          `(assoc {:netref reactnet.core/*netref*}
                 ~@(interleave
                       (c/map (comp keyword first) let-pairs#)
                       (c/map (comp symbol first) let-pairs#)))))))


(defn push!
  "Push a value to a reactive (or, pair-wise, many values to many reactives).
  m can be a netref or a map containing a :netref key. r can be a keyword
  to be lookup in n or a reactive."
  ([r v]
     (push! *netref* r v))
  ([n r v & rvs]
     (let [netref (if-let [netref (:netref n)] netref n)
           rs     (->> rvs
                       (take-nth 2)
                       (cons r)
                       (c/map #(if (keyword? %) (% n) %)))
           vs     (->> rvs
                       (c/drop 1)
                       (take-nth 2)
                       (cons v))]
       (doseq [[r v] (c/map vector rs vs)]
         (assert (reactive? r))
         (rn/push! netref r v)))
     v))


(defn complete!
  "Complete the given reactives."
  ([r]
     (complete! *netref* r))
  ([n r & rs]
     (doseq [r (cons r rs)]
       (assert (reactive? r))
       (rn/complete! n r))))


(defn reset-network!
  "Remove all links and reactives from the network, cancel all
  associated scheduled tasks."
  ([]
     (reset-network! *netref*))
  ([n]
     (-> n scheduler sched/cancel-all)
     (rn/reset-network! n)))


;; ---------------------------------------------------------------------------
;; Misc internal utils

(defn- unique-name
  "Returns a unique name with the prefix s."
  [s]
  (str (gensym s)))


(defn- countdown
  "Returns a function that accepts any args and return n times true,
  and then forever false."
  [n]
  (let [c (atom n)]
    (fn [& args]
      (<= 0 (c/swap! c dec)))))


(defn- sample-fn
  "If passed a function returns it wrapped in an exception handler.
  If passed a IDeref (atom, agent, behavior, ...) returns a function that derefs it.
  If passed any other value return (constantly value)."
  [f-or-ref-or-value]
  (cond
   (fn? f-or-ref-or-value)
   #(try (f-or-ref-or-value)
         (catch Exception ex
           (do (.printStackTrace ex)
               ;; TODO what to push in case f fails?
               ex)))
   (instance? clojure.lang.IDeref f-or-ref-or-value)
   #(deref f-or-ref-or-value)
   :else
   (constantly f-or-ref-or-value)))


(defn- derive-new
  "Creates, links and returns a new reactive which will complete if
  the link to it is removed."
  [factory-fn label inputs
   & {:keys [link-fn complete-fn error-fn]
      :or {link-fn default-link-fn}}]
  {:pre [(seq inputs)]}
  (let [new-r (factory-fn :label (unique-name label))]
    (add-links! *netref* (make-link label inputs [new-r]
                                    :link-fn link-fn
                                    :complete-fn complete-fn
                                    :error-fn error-fn
                                    :complete-on-remove [new-r]))
    new-r))


(defn- safely-apply
  "Applies f to xs, and catches exceptions.
  Returns a pair of [result exception], at least one of them being nil."
  [f xs]
  (try [(apply f xs) nil]
       (catch Exception ex [nil ex])))


(defn- make-result-map
  "Input is a Result map as passed into a Link function. If the
  exception ex is nil produces a broadcasting output-rvts, otherwise
  adds the exception. Returns an updated Result map."
  ([input value]
     (make-result-map input value nil))
  ([{:keys [output-reactives] :as input} value ex]
     (assoc input 
       :output-rvts (if-not ex (broadcast-value value output-reactives))
       :exception ex)))


(defn- make-link-fn
  "Takes a function f and wraps it's synchronous execution so that
  exceptions are caught and the return value is properly assigned to
  all output reactives.

  The optional result-fn is a function [Result Value Exception -> Result] 
  that returns the input Result map with updated values for 
  :output-rvts, :exception, :add, :remove-by, :no-consume entries.

  Returns a link function."
  ([f]
     (make-link-fn f make-result-map))
  ([f result-fn]
     {:pre [f]}
     (fn [{:keys [input-rvts] :as input}]
       (let [[v ex] (safely-apply f (values input-rvts))]
         (result-fn input v ex)))))


(defn- unpack-fn-spec
  "Returns a pair of [executor f]. If fn-spec is only a
  function (instead of a map containing both) then [nil f] is
  returned."
  [{:keys [f executor] :as fn-spec}]
  (if executor
    [executor f]
    [nil fn-spec]))


(defn- fn-spec?
  "Returns true if f is either a function or a map containing a
  function in an :f entry."
  [f]
  (or (instance? clojure.lang.IFn f)
      (and (map? f) (instance? clojure.lang.IFn (:f f)))))



;; ---------------------------------------------------------------------------
;; Enqueuing reactives and switching bewteen them

(defn- make-reactive-queue
  ([output]
     (make-reactive-queue output nil))
  ([output rs]
     {:queue (vec rs)
      :input nil
      :output output}))


(defn- switch-reactive
  [{:keys [queue input output] :as queue-state} q-atom]
  (let [r           (first queue)
        no-input?   (or (nil? input) (completed? input))]
    (if (and r no-input?)
      ;; a new r is available, and there is no current input or it is completed
      {:output output
       :queue  (vec (rest queue))
       :input  r
       :add    [(make-link "temp" [r] [output]
                           :complete-fn
                           (fn [l r]
                             (c/merge (c/swap! q-atom switch-reactive q-atom)
                                      {:remove-by #(= [r] (link-inputs %))
                                       :allow-complete #{output}})))]}
      ;; else, don't change anything
      {:output output
       :queue queue
       :input input})))


(defn- enqueue-reactive
  [queue-state q-atom r]
  (assoc (switch-reactive (update-in queue-state [:queue] conj r) q-atom)
    :dont-complete #{(:output queue-state)}))


;; ---------------------------------------------------------------------------
;; Queue to be used with an atom or agent

(defn- make-queue
  [max-size]
  {:queue (PersistentQueue/EMPTY)
   :dequeued []
   :max-size max-size})


(defn- enqueue
  [{:keys [queue dequeued max-size] :as q} v]
  (if (>= (c/count queue) max-size)
    (assoc q
      :queue (conj (pop queue) v)
      :dequeued [(first queue)])
    (assoc q
      :queue (conj queue v)
      :dequeued [])))


(defn- dequeue
  [{:keys [queue dequeued] :as q}]
  (if-let [v (first queue)]
    (assoc q
      :queue (pop queue)
      :dequeued [v])
    (assoc q
      :dequeued [])))


(defn- dequeue-all
  [{:keys [queue dequeued] :as q}]
  (assoc q
    :queue (empty queue)
    :dequeued (vec queue)))


;; ---------------------------------------------------------------------------
;; More constructors of reactives


(defn fnbehavior
  "Returns a behavior that evaluates function f whenever a value is
  requested."
  [f]
  (assoc (Fnbehavior. f)
    :label (str f)))

(declare seqstream)

(defn just
  "Returns an eventstream that evaluates x, provides the result and completes.

  x can be: 
  - an fn-spec with an arbitrary executor and a function f,
  - a function that is executed synchronously, 
  - an IDeref implementation, or 
  - a value."
  [{:keys [executor f] :as x}]
  (let [new-r  (eventstream :label (unique-name "just"))
        f      (sample-fn (if (and executor f) f x))
        task-f (fn []
                 (rn/push! new-r (f))
                 (rn/push! new-r ::rn/completed))]
    (if executor
      (execute executor *netref* task-f)
      (task-f))
    new-r))


(defn sample
  "Returns an eventstream that repeatedly evaluates x with a fixed period.
  The periodic task is cancelled when the eventstream is completed.

  x can be: 
  - an fn-spec with an arbitrary executor and a function f,
  - a function that is executed synchronously, 
  - an IDeref implementation, or 
  - a value."
  [millis {:keys [executor f] :as x}]
  (let [netref  *netref*
        s       (scheduler netref)
        new-r   (eventstream :label (unique-name "sample"))
        f       (sample-fn (if (and executor f) f x))
        task    (atom nil)
        push-f  (fn [] (if (completed? new-r)
                         (sched/cancel @task)
                         (rn/push! netref new-r (f))))
        task-f  (if (and executor f)
                  #(execute executor netref push-f)
                  push-f)]
    (reset! task (sched/interval s millis task-f))
    new-r))


(defn seqstream
  "Returns an eventstream that provides all values in collection xs
  and then completes."
  [xs & {:keys [label] :or {label (str (gensym "seq"))}}]
  (assoc (Seqstream. (atom {:seq (seq xs)
                            :last-occ nil})
                     true)
    :label label))


(defn timer
  "Returns a behavior that changes every millis milliseconds it's
  value, starting with 0, incrementing it by 1.
  The periodic task is cancelled when the eventstream is completed."
  [millis]
  (let [netref  *netref*
        s       (scheduler netref)
        ticks   (atom 0)
        new-r   (behavior 0 :label (unique-name "timer"))
        task    (atom nil)
        task-f  (fn []
                  (if (completed? new-r)
                    (sched/cancel @task)
                    (rn/push! netref new-r (c/swap! ticks inc))))]
    (reset! task (sched/interval s millis millis task-f))
    new-r))



;; ---------------------------------------------------------------------------
;; Common combinators


(declare match)


(defn amb
  "Returns an eventstream that follows the first reactive from rs that
  emits a value."
  [& rs]
  {:pre [(every? reactive? rs)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream :label (unique-name "amb"))
        f       (fn [{:keys [input-reactives input-rvts] :as input}]
                  (let [r (first input-reactives)]
                    {:remove-by #(= (link-outputs %) [new-r])
                     :add [(make-link "amb-selected" [r] [new-r] :complete-on-remove [new-r])]
                     :output-rvts (single-value (fvalue input-rvts) new-r)}))
        links   (->> rs (c/map #(make-link "amb-tentative" [%] [new-r] :link-fn f)))]
    (apply (partial add-links! *netref*) links)
    new-r))


(defn any
  "Returns an eventstream that provides true as soon as there occurs one
  truthy value in eventstream r."
  ([r]
     (any identity r))
  ([pred r]
     (match pred true false r)))


(defn buffer
  "Returns an eventstream that provides vectors of values. A non-empty
  vector occurs either after millis millisseconds, or if n items are
  available."
  [n millis r]  
  {:pre [(number? n) (number? millis) (reactive? r)]
   :post [(reactive? %)]}
  (let [netref *netref*
        s      (scheduler netref)
        b      (atom {:queue [] :dequeued nil})
        task   (atom nil)
        enq    (fn [{:keys [queue] :as q} x]
                 (let [new-queue (conj queue x)]
                   (if (and (> n 0) (>= (c/count new-queue) n))
                     (assoc q :queue [] :dequeued new-queue)
                     (assoc q :queue new-queue :dequeued nil))))
        deq    (fn [{:keys [queue] :as q}]
                 (assoc q :queue [] :dequeued queue))]
    (derive-new eventstream "buffer" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output         (first output-reactives)
                        {vs :dequeued
                         queue :queue} (c/swap! b enq (fvalue input-rvts))]
                    (when (and (>= 1 (c/count queue)) (> millis 0))
                      (c/swap! task (fn [_]
                                    (sched/once s millis
                                                (fn []
                                                  (let [vs (:dequeued (c/swap! b deq))]
                                                    (when (seq vs)
                                                      (rn/push! netref output vs))))))))
                    (if (seq vs)
                      (do (some-> task deref sched/cancel)
                          (make-result-map input vs)))))
                :complete-fn
                (fn [link r]
                  (let [vs (:queue @b)]
                    (some-> task deref sched/cancel)
                    (if (seq vs)
                      {:output-rvts (single-value vs (-> link link-outputs first))}))))))

(defn buffer-t
  "Returns an eventstream thar provides vectors of values.
  A non-empty vector occurs at most each millis milliseconds."
  [millis r]
  (buffer -1 millis r))


(defn buffer-c
  "Returns an eventstream thar provides vectors of values.
  A non-empty vector occurs when n items are available."
  [n r]
  (buffer n -1 r))


(defn changes
  "Returns an eventstream that emits pairs of values [old new]
  whenever the value of the underlying behavior changes."
  [behavior]
  {:pre [(behavior? behavior)]
   :post [(eventstream? %)]}
  (let [last-value (atom @behavior)]
    (derive-new eventstream "changes" [behavior]
                :link-fn (fn [{:keys [input-rvts output-reactives]}]
                           (let [old @last-value
                                 new (fvalue input-rvts)]
                             (reset! last-value new)
                             (if (not= old new)
                               {:output-rvts (single-value [old new] (first output-reactives))}
                               {}))))))

(declare flatmap)

(defn concat
  "Returns an eventstream that emits the items of all reactives in
  rs. It first emits items from the first reactive until it is
  completed, then from the second and so on."
  [& rs]
  {:pre [(every? reactive? rs)]
   :post [(reactive? %)]}
  (flatmap identity (seqstream rs)))


(defn count
  "Returns an eventstream that emits the number of items that were
  emitted by r."
  [r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [c (atom 0)]
    (derive-new eventstream "count" [r]
                :link-fn
                (fn [{:keys [input-rvts] :as input}]
                  (make-result-map input (c/swap! c inc))))))


(defn debounce
  "Returns an eventstream that emits an item of r not before millis
  milliseconds have passed. If a new item is emitted by r before the
  delay is passed the delay starts from the beginning."
  [millis r]
  {:pre [(number? millis) (reactive? r)]
   :post [(reactive? %)]}
  (let [task (atom nil)]
    (derive-new eventstream "debounce" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output (first output-reactives)
                        v      (fvalue input-rvts)
                        old-t  @task
                        netref *netref*
                        s      (scheduler netref)
                        new-t  (sched/once s millis #(rn/enq netref {:rvt-map {output [v (now)]}
                                                                     :results [{:allow-complete #{output}}]}))]
                    (when (and old-t (sched/pending? old-t))
                      (sched/cancel old-t))
                    (reset! task new-t)
                  {:dont-complete #{output}})))))


(defn delay
  "Returns an eventstream that emits an item of r after millis
  milliseconds."
  [millis r]
  {:pre [(pos? millis) (reactive? r)]
   :post [(reactive? %)]}
  (let [netref *netref*
        s      (scheduler netref)]
    (derive-new eventstream "delay" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output (first output-reactives)
                        v      (fvalue input-rvts)]
                    (sched/once s millis #(rn/enq netref {:rvt-map {output [v (now)]}
                                                          :results [{:allow-complete #{output}}]}))
                    {:dont-complete #{output}})))))


(defn distinct
  "Returns an eventstream that emits only distinct items of r."
  [r]
  (let [vs (atom #{})]
    (derive-new eventstream "distinct" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [v (fvalue input-rvts)]
                    (when-not (@vs v)
                      (c/swap! vs conj v)
                      {:output-rvts (single-value v (first output-reactives))}))))))


(defn drop-while
  "Returns an eventstream that skips items of r until the predicate
  pred is false for an item of r."
  [pred r]
  (derive-new eventstream "drop" [r]
              :link-fn
              (fn [{:keys [input-rvts] :as input}]
                (if-not (apply pred (values input-rvts))
                  (make-result-map input (fvalue input-rvts))))))


(defn drop
  "Returns an eventstream that first drops n items of r, all
  subsequent items are emitted."
  [n r]
  {:pre [(pos? n) (reactive? r)]
   :post [(reactive? %)]}
  (drop-while (countdown n) r))


(defn drop-last
  "Returns an eventstream that drops the last n items of r."
  [n r]
  {:pre [(pos? n) (reactive? r)]
   :post [(reactive? %)]}
  (let [q (atom (make-queue n))]
    (derive-new eventstream "drop-last" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives]}]
                  (let [vs (:dequeued (c/swap! q enqueue (fvalue input-rvts)))]
                    (if (seq vs)
                      {:output-rvts (single-value (first vs) (first output-reactives))}))))))

(defn every
  "Returns an eventstream that emits false as soon as the first falsey
  item is emitted by r."
  ([r]
     (every identity r))
  ([pred r]
     (match (complement pred) false true r)))


(defn filter
  "Returns an eventstream that emits only items of r if pred returns
  true."
  [pred r]
  {:pre [(fn-spec? pred) (reactive? r)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec pred)]
    (derive-new eventstream "filter" [r]
                :executor executor
                :link-fn
                (make-link-fn f (fn [{:keys [input-rvts] :as input} v ex]
                                  (if v
                                    (make-result-map input
                                                     (fvalue input-rvts)
                                                     ex)))))))

(defn flatmap
  "Returns an eventstream that passes items of rs to a function
  f [x ... -> Reactive], whenever all rs have a pending item. 
  Consecutively emits all items of the resulting reactives."
  [f & rs]
  {:pre [(fn-spec? f) (every? reactive? rs)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)
        new-r    (eventstream :label (unique-name "flatmap"))
        state    (atom (make-reactive-queue new-r)) ]
    (add-links! *netref* (make-link "flatmap" rs [new-r]
                                    :complete-on-remove [new-r]
                                    :executor executor
                                    :link-fn
                                    (fn [{:keys [input-rvts] :as input}]
                                      (let [r (apply f (values input-rvts))]
                                        (c/swap! state enqueue-reactive state r)))))
    new-r))


(defn hold
  "Returns a behavior that always contains the last item emitted by r."
  [r]
  {:pre [(reactive? r)]
   :post [(behavior? %)]}
  (derive-new behavior "hold" [r]))


(defn into
  "Takes items from the last given reactive and broadcasts it to all
  preceding reactives. Returns the first reactive."
  [ & rs]
  {:pre [(< 1 (c/count rs)) (every? reactive? rs)]
   :post [(reactive? %)]}
  (add-links! *netref* (make-link "into" [(last rs)] (c/drop-last rs)))
  (first rs))


(defn map
  "Returns an eventstream that emits the results of application of f
  whenever all reactives in rs have items available. You can mix
  eventstreams and behaviors."
  [f & rs]
  {:pre [(fn-spec? f) (every? reactive? rs)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)]
    (derive-new eventstream "map" rs
                :executor executor
                :link-fn
                (make-link-fn f))))


(defn mapcat
  "Returns an eventstream that emits items of collections that f
  applied to values of reactives in rs returns."
  [f & rs]
  {:pre [(fn-spec? f) (every? reactive? rs)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)]
    (derive-new eventstream "mapcat" rs
                :executor executor
                :link-fn
                (make-link-fn f (fn [input vs ex]
                                  (assoc input
                                    :output-rvts (if-not ex
                                                   (enqueue-values vs (-> input :output-reactives first)))
                                    :exception ex))))))


(defn match
  "Returns an eventstream that emits match-value and completes as soon
  as pred applied to item of r is true. Otherwise emits default-value
  on completion if no item in r matched pred."
  ([pred match-value default-value r]
  {:pre [(fn-spec? pred) (reactive? r)]
   :post [(reactive? %)]}
  (let [[executor pred] (unpack-fn-spec pred)]
    (derive-new eventstream "match" [r]
                :executor executor
                :link-fn
                (make-link-fn pred
                              (fn [{:keys [output-reactives]} v ex]
                                (if v
                                  {:output-rvts (single-value match-value (first output-reactives))
                                   :remove-by #(= output-reactives (link-outputs %))})))
                :complete-fn
                (fn [l r]
                  {:output-rvts (single-value default-value (-> l link-outputs first))})))))


(defn merge
  "Returns an eventstream that emits items from all reactives in rs in
  the order they arrive."
  [& rs]
  {:pre [(every? reactive? rs)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream :label (unique-name "merge"))
        inputs  (atom (set rs))
        links   (->> rs (c/map #(make-link "merge" [%] [new-r]
                                           :complete-fn
                                           (fn [l r]
                                             (if-not (seq (c/swap! inputs disj r))
                                               {:output-rvts (single-value ::rn/completed new-r)})))))]
    (if (seq links)
      (apply (partial add-links! *netref*) links)
      (complete! new-r))
    new-r))


(defn reduce
  "Returns an eventstream that applies the reducing function f to the
  accumulated value (initially seeded with initial-value) and items
  emitted by r. Emits a result only upon completion of r."
  [f initial-value r]
  {:pre [(fn-spec? f) (reactive? r)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)
        accu         (atom initial-value)]
    (derive-new eventstream "reduce" [r]
                :executor executor
                :link-fn
                (make-link-fn (partial c/swap! accu f)
                              (constantly nil))
                :complete-fn
                (fn [l r]
                  {:output-rvts (single-value @accu
                                              (-> l link-outputs first))}))))


(defn remove
  "Returns an eventstream that drops items from r if they match the
  predicate pred."
  [pred r]
  (filter (if (map? pred)
            (update-in pred [:f] complement)
            (complement pred))
          r))


(defn scan
  "Returns an eventstream that applies the reducing function f to the
  accumulated value (initially seeded with initial-value) and items
  emitted by r. Emits each result of f."
  [f initial-value r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [[executor f] (unpack-fn-spec f)
        accu         (atom initial-value)]
    (derive-new eventstream "scan" [r]
                :link-fn
                (make-link-fn #(c/swap! accu f %)))))


(defn sliding-buffer
  "Returns an eventstream that emits vectors of items from r, with a
  maximum of n items. When n is reached drops the oldest item and
  conjoins the youngest item from r."
  [n r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [q (atom [])]
    (derive-new eventstream "sliding-buffer" [r]
                :link-fn
                (fn [{:keys [input-rvts output-reactives]}]
                  (let [vs (c/swap! q
                            (fn [items]
                              (conj (if (>= (c/count items) n)
                                      (vec (c/drop 1 items))
                                      items)
                                    (fvalue input-rvts))))]
                    {:output-rvts (broadcast-value vs output-reactives)})))))


(defn startwith
  "Returns an eventstream that first emits items from start-r until
  completion, then emits items from r."
  [start-r r]
  (concat start-r r))


(defn switch
  "Returns an eventstream that emits items from the latest reactive
  emitted by r."
  [r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [new-r (eventstream :label (unique-name "switch"))]
    (add-links! *netref*
                (make-link "switcher" [r] [] ;; must not point to an output reactive
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (let [r (fvalue input-rvts)]
                               {:remove-by #(= (link-outputs %) [new-r])
                                :add (if (reactive? r)
                                       [(make-link "switch" [r] [new-r])])}))))
    new-r))


(defn snapshot
  "Returns an eventstream that emits the evaluation of x everytime it
  receives an item from r.

  x can be: 
  - an fn-spec with an arbitrary executor and a function f,
  - a function that is executed synchronously, 
  - an IDeref implementation, or 
  - a value."
  [{:keys [executor f] :as x} r]
  {:pre [(reactive? r)]
   :post [(reactive? %)]}
  (let [netref *netref*
        f      (if executor
                 #(execute executor netref f)
                 (sample-fn x))]
    (derive-new eventstream "snapshot" [r]
                :link-fn
                (fn [{:keys [output-reactives]}]
                  {:output-rvts (single-value (f) (first output-reactives))}))))


(defn subscribe
  "Attaches a 1-arg function f to r that is invoked whenever r emits
  an item.  Instead of a function, f can be an fn-spec containing an
  arbitrary executor and a function that is executed by the executor
  on a different thread.
  Returns r."
  ([f r]
     (subscribe (gensym "subscriber") f r))
  ([key f r]
     {:pre [(fn-spec? f) (reactive? r)]
      :post [(reactive? %)]}
     (let [[executor f] (unpack-fn-spec f)]
       (add-links! *netref* (assoc (make-link "subscriber" [r] []
                                              :link-fn (make-link-fn f (constantly {}))
                                              :executor executor)
                              :subscriber-key key))
       r)))


(defn swap!
  "Swaps the value of atom a with the value of the result of (f
  value-of-a item) whenever r emits an item.
  Returns r."
  [a f r]
  (subscribe (partial c/swap! a f) r))


(defn take-while
  "Returns an eventstream that emits items from r as long as they
  match predicate pred."
  [pred r]
  {:pre [(fn-spec? pred) (reactive? r)]
   :post [(reactive? %)]}
  (derive-new eventstream "take-while" [r]
              :link-fn
              (fn [{:keys [input-rvts output-reactives] :as input}]
                (if (apply pred (values input-rvts))
                  {:output-rvts (single-value (fvalue input-rvts) (first output-reactives))}
                  {:no-consume true
                   :remove-by #(= (link-outputs %) output-reactives)}))))


(defn take
  "Returns an eventstream that emits at most n items from r."
  [n r]
  {:pre [(pos? n) (reactive? r)]
   :post [(reactive? %)]}
  (let [counter  (atom n)
        new-r    (derive-new eventstream "take" [r]
                             :link-fn
                             (fn [{:keys [input-rvts output-reactives] :as input}]
                               (if (> (c/swap! counter dec) 0)
                                 {:output-rvts (single-value (fvalue input-rvts)
                                                             (first output-reactives))}
                                 {:output-rvts (enqueue-values [(fvalue input-rvts)
                                                                ::rn/completed]
                                                             (first output-reactives))
                                  :remove-by #(= (link-outputs %) output-reactives)})))]
    (when (= 0 n)
      (complete! new-r))
    new-r))


(defn take-last
  "Returns an eventstream that emits the last n items from r."
  [n r]
  {:pre [(pos? n) (reactive? r)]
   :post [(reactive? %)]}
  (let [q (atom (make-queue n))]
    (derive-new eventstream "take-last" [r]
                :link-fn
                (fn [{:keys [input-rvts]}]
                  (c/swap! q enqueue (fvalue input-rvts)))
                :complete-fn
                (fn [l r]
                  {:output-rvts (enqueue-values (-> q deref :queue)
                                                (-> l link-outputs first))}))))


(defn throttle
  "Returns an eventstream that queues items from r. Emits items with
  on a fixed time period by applying f to the collection of queued
  items.  If more than max-queue-size items arrive in a period these
  are not consumed.
  The periodic task is cancelled when the eventstream is completed."
  [f millis max-queue-size r]
  {:pre [(fn-spec? f) (pos? millis) (pos? max-queue-size) (reactive? r)]
   :post [(reactive? %)]}
  (let [netref *netref*
        s      (scheduler netref)
        q      (atom (make-queue max-queue-size))
        new-r  (derive-new eventstream "throttle" [r]
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (if (>= (-> q deref :queue c/count) max-queue-size)
                               {:no-consume true}
                               (c/swap! q enqueue (fvalue input-rvts)))))
        task   (atom nil)
        task-f (fn []
                 (if (completed? new-r)
                   (sched/cancel @task)
                   (let [vs (:dequeued (c/swap! q dequeue-all))]
                     (when-not (empty? vs)
                       (rn/push! netref new-r (f vs))))))]
    (reset! task (sched/interval s millis millis task-f))
    new-r))


(defn unsubscribe
  "Detaches the listener function that the key denotes from reactive r.
  Returns r."
  [key r]
  (remove-links! *netref* #(and (= (:subscriber-key %) key)
                                (= (link-inputs %) [r])))
  r)


;; ---------------------------------------------------------------------------
;; Expression lifting


(defn ^:no-doc as-behavior
  [label x]
  (if (behavior? x)
     x
    (behavior x :label label)))


(defn- as-set
  [x]
  (if (coll? x) (set x) (set (list x))))


(defn ^:no-doc and-f
  [& xs]
  (if (next xs)
    (and (first xs) (apply and-f (rest xs)))
    (first xs)))


(defn ^:no-doc or-f
  [& xs]
  (if (next xs)
    (or (first xs) (apply or-f (rest xs)))
    (first xs)))


(defn ^:no-doc lift-fn
  [f & rs]
  (derive-new behavior "lift-fn" rs
              :link-fn
              (make-link-fn f)))


(defn ^:no-doc lift-if
  [test-b then-b else-b]
  (derive-new behavior "lift-if" [test-b then-b else-b]
              :link-fn
              (make-link-fn (fn [test then else]
                              (if test then else)))))


(defn ^:no-doc lift-cond
  [& test-expr-bs]
  (derive-new behavior "lift-cond" test-expr-bs
              :link-fn
              (make-link-fn (fn [& args]
                              (let [[test-value
                                     expr-value] (->> args
                                                      (partition 2)
                                                      (c/drop-while (comp not first))
                                                      first)]
                                expr-value)))))

(defn ^:no-doc lift-case
  [expr-b test-value-sets expr-bs]
  (derive-new behavior "lift-case" (c/into [expr-b] expr-bs)
              :link-fn
              (make-link-fn (fn [v & rvs]
                              (let [rs (c/remove nil? (c/map #(if (%1 v) %2)
                                                             test-value-sets rvs))]
                                (if (seq rs)
                                  (first rs)
                                  (let [default (last rvs)]
                                    (if (= ::no-default default)
                                      (throw (IllegalArgumentException. (str "No matching clause for " v)))
                                      default))))))))

(defn- lift-exprs
  [exprs]
  (c/map (fn [expr] `(lift ~expr)) exprs))


(defn- lift-dispatch
  [expr]
  (if (list? expr)
    (if (#{'if 'cond 'case 'let 'and 'or} (first expr))
      (first expr)
      'fn-apply)
    'symbol))


(defmulti ^:private lift*
  #'lift-dispatch)


(defmacro lift
  "Lifts an expression to operate on behaviors. 
  Returns a behavior that is updated whenever a value 
  of a behavior referenced in the expression is updated.

  Supports function application, let, cond, case, and, or."
  [expr]
  (lift* expr))


(defmethod lift* 'symbol
  [expr]
  `(as-behavior ~(str expr) ~expr))


(defmethod lift* 'fn-apply
  [[f & args]]
  `(lift-fn ~f ~@(lift-exprs args)))


(defmethod lift* 'if
  [[_ test-expr then-expr else-expr]]
  `(lift-if (lift ~test-expr) (lift ~then-expr) (lift ~else-expr)))


(defmethod lift* 'let
  [[_ bindings & exprs]]
  `(let ~(c/into []
               (c/mapcat (fn [[sym expr]]
                           [sym `(lift ~expr)])
                         (partition 2 bindings)))
     ~@(lift-exprs exprs)))


(defmethod lift* 'cond
  [[_ & exprs]]
  `(lift-cond ~@(lift-exprs exprs)))


(defmethod lift* 'case
  [[_ expr & clauses]]
  (let [default   (if (odd? (c/count clauses))
                    (last clauses)
                    ::no-default)
        test-sets (->> clauses
                       (partition 2)
                       (c/map first)
                       (c/map as-set))
        exprs     (conj (->> clauses
                             (c/drop 1)
                             (take-nth 2)
                             vec)
                        default)]
    `(lift-case (lift ~expr) [~@test-sets] [~@(lift-exprs exprs)])))


(defmethod lift* 'and
  [[_ & exprs]]
  `(lift-fn and-f ~@(lift-exprs exprs)))


(defmethod lift* 'or
  [[_ & exprs]]
  `(lift-fn or-f ~@(lift-exprs exprs)))


;; ---------------------------------------------------------------------------
;; Error handling


(defn err-ignore
  "Attaches an exception-handler to the function calculating the items for r.
  Quietly ignores any exception, and does not even print a stacktrace.
  Returns r."
  [r]
  (on-error *netref* r
            (fn [result] {})))


(defn err-retry-after
  "Attaches an exception-handler to the function calculating the items for r.
  The function evaluation is retried after millis milliseconds.
  Returns r."
  [millis r]
  (let [netref *netref*
        s      (scheduler netref)]
    (on-error *netref* r
              (fn [{:keys [input-rvts]}]
                (sched/once s millis #(enq netref {:rvt-map (c/into {} (vec input-rvts))
                                                   :results [{:allow-complete #{r}}]}))
                {:dont-complete #{r}}))))


(defn err-return
  "Attaches an exception-handler to the function calculating the items for r.
  Upon exception, r emits x.
  Returns r."
  [x r]
  (on-error *netref* r
            (fn [{:keys [output-reactives]}]
              {:output-rvts (single-value x (first output-reactives))})))


(defn err-switch
  "Attaches an exception-handler to the function calculating the items for r.
  Upon exception, r is permanently switched to follow reactive r-after-error.
  Returns r."
  [r-after-error r]
  {:pre [(reactive? r-after-error) (reactive? r)]}
  (on-error *netref* r
            (fn [{:keys [input-reactives]}]
              {:remove-by #(= (link-outputs %) [r])
               :dont-complete #{r}
               :add [(make-link "err" [r-after-error] [r]
                                :link-fn default-link-fn)]})))


(defn err-into
  "Attaches an exception-handler to the function calculating the items for r.
  Redirects a map containing the exception in an :exception entry and
  the input values in an :input-rvts entry to the reactive error-r.
  Returns r."
  [error-r r]
  (on-error *netref* r
            (fn [input]
              (enq *netref* {:rvt-map {error-r [input (now)]}}))))


(defn err-delegate
  "Attaches an exception-handler to the function calculating the items for r.
  Upon exception, f is invoked with a map containing the exception and
  the input value. The result of f must be a network stimulus and is enqueued 
  for propagation. 
  Returns r."
  [f r]
  (on-error *netref* r
            (fn [input]
              (enq *netref* (f input)))))


;; ---------------------------------------------------------------------------
;; Async execution


(defn in-future
  "Returns an fn-spec that wraps f to be executed by a FutureExecutor."
  [f]
  {:f f :executor (FutureExecutor.)})
