(ns reactor.scenarios-test
  (:require [clojure.test :refer :all]
            [clojure.set :refer [difference]]
            [reactor.core :as r]
            [reactnet.core :as rn]
            [reactnet.debug :as dbg]
            [reactnet.monitor :as mon]))


(defn >!
  [system key value]
  (r/push! (:netref system) (get system key) value))


;; ---------------------------------------------------------------------------
;; EIP split-aggregate

(def vendor-offers {"v1" 100
                    "v2" 50
                    "v3" 300
                    "v4" 150})


(defn get-offer
  [vendor request]
  (Thread/sleep (rand-nth [100 300 500]))
  {vendor (vendor-offers vendor)})



(defn get-offer-stream
  [[request vendor]]
  (->> (r/merge (->> (r/just {vendor -1}) (r/delay 20))
                (r/just (r/in-future #(get-offer vendor request))))
       (r/take 1)))


(defn collect-offers-stream
  [request]
  (->> (r/map vector
              (r/seqstream (repeat request))
              (r/seqstream (keys vendor-offers)))
       (r/flatmap (r/in-future get-offer-stream))
       (r/reduce (fn [accu bid]
                   (update-in accu [:offers] merge bid))
                  request)))


(defn make-split-aggregate-rs
  []
  (r/setup-network ""
                 i       (r/eventstream)
                 results (atom [])
                 b       (->> i
                              (r/flatmap collect-offers-stream)
                              (r/swap! results conj)
                              (r/hold))))

(defn examine
  [results]
  (->> results
       (filter #(= (-> vendor-offers keys set) (-> % :offers keys set)))
       count))


(defn print-stats
  [results a]
  (println "Results" (count @results)
           "Agent Queue" (.getQueueCount a)
           "Agent Error" (agent-error a)
           "Reactives" (-> @a :rid-map count)
           "Links" (-> @a :links count)
           "Removes" (-> @a :pending-removes)))


(deftest split-aggregate-test
  (let [s        (make-split-aggregate-rs)
        a        (-> s :netref :n-agent)
        results  (:results s)
        n        50
        start    (System/currentTimeMillis)]
    (mon/reset-all! (-> s :netref rn/monitors))
    (dotimes [i n]
      #_(Thread/sleep 50)
      (>! s :i {:p i}))
    (loop []
      (Thread/sleep 500)
      (print-stats results a)
      (if (> n (count @results))
        (recur)))
    #_(clojure.pprint/pprint @results)
    (let [stop (System/currentTimeMillis)
          diff (- stop start)
          accomplished (examine @results)]
      (println "Duration" (float (/ diff 1000)) "seconds")
      (println (float (* 1000 (/ accomplished diff))) "results per second")
      (print-stats results a)
      (is (= n accomplished)))
    (mon/print-all (-> s :netref rn/monitors))))


(defn run []
  (dbg/clear)
  (dbg/on)
  (split-aggregate-test)
  (dbg/to-file (dbg/lines)))

#_(dbg/to-console (dbg/lines (dbg/matches-reactive "reduce")))
