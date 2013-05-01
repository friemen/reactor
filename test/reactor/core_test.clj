(ns reactor.core-test
  (:use clojure.test
        reactor.core))

(deftest bind-test
  (let [n1 (make-signal 0)
          n2 (make-signal 0)
          sum (-> (make-signal 0) (bind + n1 n2))
          sum>10 (-> sum
                     (trigger #(when (> % 10) "ALARM!"))
                     (react-with #(println %)))]
      (set-value! n1 3)
      (is (= 3 (get-value sum)))
      (set-value! n2 9)
      (is (= 12 (get-value sum)))))


(deftest trigger-test
  (let [n (make-signal 0)
        alarm (-> n (trigger #(when (> % 10) "ALARM!")))]
    (set-value! n 9)
    (is (= nil (consume-event! alarm)))
    (set-value! n 11)
    (is (= "ALARM!" (consume-event! alarm)))))
