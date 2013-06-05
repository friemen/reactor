(ns reactor.propagation-test
  (:use [clojure.test]
        [reactor.propagation]))

(def t1 (atom nil))
(def t2 (atom nil))
(def t3 (atom nil))
(def p1 (propagator #(do (reset! t1 (/ % 2)) (reset! t2 (* % 2))) [t1 t2]))
(def p2 (propagator #(do (reset! t3 (+ % 2))) [t3]))


(deftest propagator-test
  (is (= #{t1 t2}
         (:targets (propagator identity [t1 t2]))))
  (is (= #{t1}
         (:targets (propagator identity [t1 t1])))))

(deftest propagate-test
  (is (= [3/2 6]
         (do ((:fn p1) 3)
             [@t1 @t2]))))

(deftest add-remove-test
  (let [ps (-> (propagator-set)
               (doto (add! p1) (add! p2)))]
    (is (= #{p1 p2} (propagators ps)))
    (remove! ps p1)
    (is (= #{p2} (propagators ps)))))

(deftest propagate-all-test
  (let [ps (-> (propagator-set)
               (doto (add! p1) (add! p2)))]
    (propagate-all! ps 3)
    (is (= [3/2 6 5] [@t1 @t2 @t3]))))