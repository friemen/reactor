reactor
=======

Exploring functional reactive programming (FRP) with Clojure.

[![Build Status](https://travis-ci.org/friemen/reactor.png?branch=master)](https://travis-ci.org/friemen/reactor)

The purpose of the factories and combinators as implemented here 
is to enable declarative specifications of event and signal
processing chains (using the ->> macro and familiar functions
like filter, map or merge).

Concepts
--------

An *event* is something non-continuous that "happens", represented by a value.

An *occurence* contains event and timestamp.

An *event source* publishes occurences to subscribers. 

A *signal* (a.k.a *behaviour*) is a value that possibly changes over time.

A *reactive* is an abstraction of event source and signal. 

A *follower* is a function or reactive that is affected by events or value changes.

(The terms *signal* and *event source* were inspired by the paper 
[Genuinely Functional User Interfaces](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/genuinely-functional-guis.pdf).)

Examples
--------

Example for event processing:
```clojure
(def e1 (r/eventsource))
(def e2 (r/eventsource))

(->> (r/merge e1 e2)
     (r/filter #(not= % "World"))
     (r/react-with #(println "EVENT:" %)))

(r/raise-event! e1 "Hello")
(r/raise-event! e2 "World")
;=> prints occurence with "Hello", but "World" is suppressed
```

Example for signal processing:
```clojure
(do (def n1 (r/signal 0))
	(def n2 (r/signal 1)))

(def n1*3+n2 (r/lift (if (> n1 0)
	                     (let [n1*2 (+ n1 n1)
				               n1*3 (+ n1*2 n1)]
				            (+ n2 n1*3)))))
;=> #'user/n1*3+n2

(r/setvs! [n1 n2] [3 7]) ; signal is updated whenever n1 or n2 changes.
;=> nil

(r/getv n1*3+n2)
;=> 16


(def sum (r/lift (+ n1 n2)))
;=> #'user/sum

(def sum>10 (->> sum
                 (r/changes #(when (> % 10) "ALARM!"))
                 (r/react-with #(println %))))
;=> #'user/sum>10

; sum>10 is an event source. Whenever sum's value > 10
; an occurence containing "ALARM!" is printed.
```
       
Further examples:

[Swing animation example](src/reactor/swing_sample.clj).


Current state
-------------
This library is currently purely experimental stuff.
The goal for now is to produce an API that supports the formulation of FRP-based solutions.

- There already is a rich set of **combinators** on signals and event sources.

- Signals can be created by **expression lifting** that works for basic language elements like function application, let and if.

- The propagation is handled by an **execution engine** that processes *propagation entries* 
in the topological order of the reactive network, which avoids inconsistencies and allows 
cyclic dependencies among reactives.

- The dependencies among reactives form an explicit graph. Updates are made via a push-based approach.

- Event and signal processing can be **passed across threads** and therefore allows for chains
with asynchronity.

Second step is to provide some more elaborate samples that demonstrate how non-trivial
applications like GUI, message processing, animation or games would be described with FRP.


API Overview
------------
See also [core.clj](src/reactor/core.clj).

### Default Reactives

**time** -- A signal that is regularly updated and always returns the current epoch time in milliseconds.

**exceptions** -- An event source that all caught exceptions are directed to.


### Factories

**signal** -- Creates a new signal with the given initial value.

**eventsource** -- Creates a new event source.


### Functions applying to Signals as well as Event Sources

**pass** -- Creates a reactive that handles propagation/processing in a different thread. 

**react-with** -- Subscribes a listener function to an event source or signal.


### Functions applying to Event Sources

**raise-event!** -- Creates a new occurence. The event will be propagated to all followers.

**hold** -- Creates a signal from an event source where the event of the last occurence is stored as signal value.

**map** -- Creates a new event sources that transforms every event.

**filter** -- Creates a new event source that propagates event if predicate on event returns true. 

**remove** -- Creates a new event source that omits event if predicate on event returns true. 

**delay** -- Creates a new event source that propagates occurences from a given event source with a delay.

**calm** -- Creates a new event source that propagates occurences from a given event source with a delay. If an occurence arrives while the delay is active the previous occurence is omitted. 

**merge** -- Creates a new event source that emits an occurence whenever one of its source event sources emits an occurence.

**switch** -- Creates a signal from an event source that follows signals that were delivered as event. 

**reduce** -- Creates a signal from an event source that changes its value by applying a function to the current value of the signal and an event. 

**reduce-t** -- Like reduce, but passes a third argument for elapsed time since last update of the new signal.

**snapshot** -- Creates an a new event source from an existing event source that takes a value from another signal whenever an event occurs.


### Functions applying to Signals

**as-signal** -- Takes an arbitraty value. If it's a signal, returns it unchanged. If it's an event source returns a new signal that holds the last event. Otherwise returns a new signal with the given value.

**elapsed-time** -- Creates a signal that holds the elapsed time between now and the last update of a signal.

**setv!** -- Sets the value of one signal. If the old value and the new value are different the value is propagated to followers of the signal.

**setvs!** -- Sets values for some signals, values are propagated if respective old and new values differ.

**getv** -- Returns the current value of the signal.

**behind** -- Creates a new signal that follows a given signal with a lag.

**changes** -- Creates a new event source from a signal that emits an occurence everytime the value is changed. The event is the pair [old-value new value].

**apply** -- Creates a new signal that applies a function to the values of all signals whenever a value changes. The new signal stores the result of the function application.

**lift** -- Macro that takes a sexp, lifts it (and all subexpressions) and returns a signal that changes whenever a value of the signals of the sexp changes.

**bind!** -- Connects input with output signals. On every value change a function is applied to current input values. The resulting values are set as value to the output signals.

**process-with** -- Subscribes a listener function to a signal that is invoked upon value changes.


### Functions for controlling execution 

By default the engine is NOT started, but works in an auto execute mode which is helpful
for unit tests and in the REPL.

**start-engine!** -- Starts execution loop with the specified delay.

**stop-engine!** -- Stops execution loop.

**reset-engine!** -- Removes all queued propagation entries from the engine.

       

References
----------

E.Amsden - [A Survey of Functional Reactive Programming](http://www.cs.rit.edu/~eca7215/frp-independent-study/Survey.pdf)

A.Courtney - [Frappe: Functional Reactive Programming in Java](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/frappe-padl01.pdf)

A.Courtney, C.Elliot - [Genuinely Functional User Interfaces](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/genuinely-functional-guis.pdf)

E.Czaplicki - [The ELM Programming Language](http://elm-lang.org)

C.Elliot, P.Hudak - [Functional Reactive Animation](http://conal.net/papers/icfp97/icfp97.pdf)

C.Elliot - [Push-pull functional reactive programming](http://conal.net/papers/push-pull-frp/push-pull-frp.pdf)

I.Maier, T.Rompf, M.Odersky - [Deprecating the Observer Pattern](http://lamp.epfl.ch/~imaier/pub/DeprecatingObserversTR2010.pdf)

L.Meyerovich - [Flapjax: Functional Reactive Web Programming](http://www.cs.brown.edu/research/pubs/theses/ugrad/2007/lmeyerov.pdf)


License
=======

Copyright 2013 F.Riemenschneider

Distributed under the Eclipse Public License, the same as Clojure.
