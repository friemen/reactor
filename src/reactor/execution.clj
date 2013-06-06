(ns reactor.execution
  "Functions supporting fn execution in different thread and with periodic scheduling."
  (:import [java.util.concurrent Executors ThreadPoolExecutor TimeUnit ScheduledThreadPoolExecutor]
           [javax.swing SwingUtilities]))


(defprotocol Executor
  (schedule [exec f])
  (shutdown [exec]))

(defonce ^:private executors (atom #{}))

(def current-thread (reify Executor
                          (schedule [_ f] (f))
                          (shutdown [_] nil)))

(def ui-thread (reify Executor
                     (schedule [_ f] (SwingUtilities/invokeLater f))
                     (shutdown [_] nil)))

(defn executor
  "Creates an executor that takes a function an executes it in a different thread."
  ([]
     (executor 1))
  ([thread-number]
     (let [exec (Executors/newFixedThreadPool thread-number)]
       (swap! executors #(conj % exec))
       exec)))

(extend-type ThreadPoolExecutor
  Executor
  (schedule [exec f]
    (.execute exec f))
  (shutdown [exec]
    (.shutdown exec)
    (swap! executors #(disj % exec))))

(deftype Timer [period exec task]
  Executor
  (schedule [t f]
    (swap! task (fn [sft]
                  (when sft (.cancel sft false))
                  (-> t .exec (.scheduleAtFixedRate f 0 period TimeUnit/MILLISECONDS)))))
  (shutdown [t]
    (-> t .exec .shutdown)
    (swap! executors #(disj % exec))))

(defn timer-executor
  "Create a scheduled executor that on schedule starts a function periodically repeating."
  [period]
  (let [exec (Timer. period (ScheduledThreadPoolExecutor. 1) (atom nil))]
    (swap! executors #(conj % exec))
    exec))

(defn shutdown-all
  "Shuts all executors down."
  []
  (doseq [exec @executors]
    (shutdown exec)))


(defonce ^:private stopper (Thread. shutdown-all))

(doto (java.lang.Runtime/getRuntime)
  (.removeShutdownHook stopper)
  (.addShutdownHook stopper))

