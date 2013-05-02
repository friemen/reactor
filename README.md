reactor
=======

Exploring functional reactive programming with Clojure.

The purpose of the factories and combinators as implemented here 
is to enable declarative specifications of event and signal
processing chains (using the -> macro).


Concepts
--------

An event is something non-continuous that "happens".

An occurence is a pair [timestamp, event].

A signal (a.k.a behaviour) is a value that possibly changes over time.


Examples
--------

Example for event processing:

    (def e1 (make-eventsource))
    (def e2 (make-eventsource))
    
    (-> (aggregate e1 e2)
        (allow #(not= % "World"))
        (react-with #(println "EVENT:" %)))
    
    (raise-event! e1 "Hello")
    (raise-event! e2 "World")
    => prints "Hello"

Example for signal processing:

    (def n1 (make-signal 0))
    (def n2 (make-signal 0))
    
    (def sum (-> (make-signal 0) (bind + n1 n2)))
    (set-values! [n1 n2] [3 7])
    => sum == 10, and sum is updated whenever n1 or n2 changes.
    
    (def sum>10 (-> sum
                   (trigger #(when (> % 10) "ALARM!"))
                   (react-with #(println %))))
    => sum>10 is an event source. whenever sum's value > 10
       the string "ALARM!" is printed.


Current state
-------------
This library is currently purely experimental stuff.

The implementation of EventSource and Signal is now based on Clojure atoms, 
which is only one way. It seems -- for some use cases --
entirely reasonable to base the implementation on queues and/or agents. 

Occurences (pairs of time and event) are in the moment not represented in the code.


