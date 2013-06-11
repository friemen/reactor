(ns reactor.execution
  "Functions supporting fn execution in different thread and with periodic scheduling."
  (:import [java.util.concurrent Executors ThreadPoolExecutor
            TimeUnit ScheduledThreadPoolExecutor]
           [javax.swing SwingUtilities]))


(defprotocol Executor
  (schedule [exec f])
  (cancel [exec]))


(def current-thread (reify Executor
                          (schedule [_ f] (f))
                          (cancel [_] false)))

(def ui-thread (reify Executor
                     (schedule [_ f] (SwingUtilities/invokeLater f))
                     (cancel [_] false)))

(defonce ^:private tp (Executors/newCachedThreadPool))
(defonce ^:private stp (ScheduledThreadPoolExecutor. 10))

(defn- invocation-target
  [exec f delay]
  (if (= delay 0)
    f
    (fn []
      (try
        (Thread/sleep delay)
        (f)
        (catch InterruptedException ex nil)))))


(defrecord SimpleExecutor [delay cancel-current future-atom]
  Executor
  (schedule [exec f]
    (swap! future-atom (fn [t]
                         (when (and t cancel-current)
                           (.cancel t true))
                         (.submit tp (invocation-target exec f delay)))))
  (cancel [exec]
    (if-let [t @future-atom] (.cancel t true))))

(defn executor
  "Creates an executor that - upon schedule - executes a function
   in a different thread. A subsequent call to schedule will not
   cancel already scheduled functions."
  []
  (SimpleExecutor. 0 false (atom nil)))


(defn delayed-executor
  "Creates an executor that -upon schedule - executes a function
   in a different thread after waiting for the amount
   of milliseconds specified by delay.
   A subsequent call to schedule will not cancel already
   scheduled functions."
  [delay]
  (SimpleExecutor. delay false (atom nil)))


(defn calmed-executor
  "Creates an executor that - upon schedule - executes a function in a
   different thread after waiting for the amount
   of milliseconds specified by delay.
   A subsequent call to schedule will cancel the function
   that has been scheduled before."
  [delay]
  (SimpleExecutor. delay true (atom nil)))


(defrecord TimerExecutor [period future-atom]
  Executor
  (schedule [exec f]
    (swap! future-atom (fn [t]
                  (when t (.cancel t false))
                  (-> stp (.scheduleAtFixedRate f 0 period TimeUnit/MILLISECONDS)))))
  (cancel [exec]
    (if-let [t @future-atom] (.cancel t true))))

(defn timer-executor
  "Create a scheduled executor that - upon schedule -
   periodically executes the given function. A subsequent
   call to schedule will stop the previous function from
   being executed."
  [period]
  (TimerExecutor. period (atom nil)))


(defn shutdown-pools
  []
  (.shutdown tp)
  (.shutdown stp))

(defonce ^:private stopper (Thread. shutdown-pools))

(doto (java.lang.Runtime/getRuntime)
  (.removeShutdownHook stopper)
  (.addShutdownHook stopper))

