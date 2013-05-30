reactor
=======

Exploring functional reactive programming with Clojure.

The purpose of the factories and combinators as implemented here 
is to enable declarative specifications of event and signal
processing chains (using the ->> macro and familiar functions
like filter, map or merge).


Concepts
--------

An event is something non-continuous that "happens".

An occurence is a pair [event timestamp].

A signal (a.k.a behaviour) is a value that possibly changes over time.


Examples
--------

Example for event processing:

    (def e1 (r/eventsource))
    (def e2 (r/eventsource))
    
    (->> (r/merge e1 e2)
         (r/filter #(not= % "World"))
         (r/react-with #(println "EVENT:" %)))
    
    (r/raise-event! e1 "Hello")
    (r/raise-event! e2 "World")
    => prints "[Hello 1369...]"

Example for signal processing:

    (def n1 (r/signal 0))
    (def n2 (r/signal 0))
    
    (def sum (->> (r/lift + n1 n2)))
    (r/setvs! [n1 n2] [3 7])
    => sum == 10, and sum is updated whenever n1 or n2 changes.
    
    (def sum>10 (->> sum
                     (r/trigger #(when (> % 10) "ALARM!"))
                     (r/react-with #(println %))))
    => sum>10 is an event source. whenever sum's value > 10
       the string "[ALARM! 1369...]" is printed.


Current state
-------------
This library is currently purely experimental stuff.

The implementation of EventSource and Signal is now based on Clojure atoms, 
which is only one way. It seems -- for some use cases --
entirely reasonable to base the implementation on refs, queues and/or agents. 


