reactor
=======

Exploring functional reactive programming (FRP) with Clojure.

The purpose of the factories and combinators as implemented here 
is to enable declarative specifications of event and signal
processing chains (using the ->> macro and familiar functions
like filter, map or merge).


Concepts
--------

An *event* is something non-continuous that "happens".

An *occurence* is a pair [event timestamp].

An *event source* publishes occurences to subscribers. 

A *signal* (a.k.a *behaviour*) is a value that possibly changes over time.

A *reactive* is an abstraction over event source and signal. 

A *follower* is a function or reactive that is affected by events or value changes.


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

API
---
See also [core.clj](src/reactor/core.clj).

### Functions applying to Event Sources

**raise-event!** -- Creates a new occurence. The event will be propagated to all followers.

**as-signal** -- Creates a signal from a constant value or an event source. In the latter case the event of an occurence is stored in a signal.

**filter** -- Creates a new event source that applies a predicate to decide whether an occurence is propagated. 

**map** -- Creates a new event sources that transforms every event.

**merge** -- Creates a new event source that emits an occurence whenever one of its source event sources emits an occurence.

**switch** -- Creates a signal from an event source that follows signals that were delivered as event. 

**reduce** -- Creates a signal from an event source that changes its value by applying a function to the current value of the signal and an event. 

**react-with** -- Subscribes a listener function to an event source.

**unsubscribe** -- Unsubscribes a listener function from an event source.


### Functions applying to Signals

**setv!** -- Sets the value of one signal. If the old value and the new value are different the value is propagated to followers of the signal.

**setvs!** -- Sets values for some signals, values are propagated if respective old and new values differ.

**getv** -- Returns the current value of the signal.

**trigger** -- Creates a new event source from a signal that emits an occurence everytime the value is changed. The new value is taken as event.

**lift** -- Creates a new signal that applies a function to the values of all signals whenever a value changes. The new signal stores the result of the function application.

**bind!** -- Connects input with output signals. On every value change a function is applied to current input values. The resulting values are set as value to the output signals.

**process-with** -- Subscribes a listener to the signal.

       
Current state
-------------
This library is currently purely experimental stuff.
The goal for now is to produce an API that supports the FRP programming model.
Second step is to provide some more elaborate samples that demonstrate how non-trivial
applications like GUI, message processing, animation or games would be described with FRP.
If those samples provide evidence that FRP really simplifies the program code in certain 
areas, the implementation "under the hood" would be improved.

The current implementation is very simple, it is just enough to make the API working.
It uses atoms for signal values.
Event and signal processing is single-threaded.
It does not avoid inconsistencies (a.k.a glitches).

References
----------

C.Elliot, P.Hudak - [Functional Reactive Animation](http://conal.net/papers/icfp97/icfp97.pdf)

C.Elliot - [Push-pull functional reactive programming](http://conal.net/papers/push-pull-frp/push-pull-frp.pdf)

A.Courtney - [Frappe: Functional Reactive Programming in Java](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/frappe-padl01.pdf)

I.Maier, T.Rompf, M.Odersky - [Deprecating the Observer Pattern](http://lamp.epfl.ch/~imaier/pub/DeprecatingObserversTR2010.pdf)

E.Amsden - [A Survey of Functional Reactive Programming](http://www.cs.rit.edu/~eca7215/frp-independent-study/Survey.pdf)
