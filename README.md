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

An *event* is something non-continuous that "happens".

An *occurence* is a pair [event timestamp].

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

(r/setvs! [n1 n2] [3 7]) ; sum is updated whenever n1 or n2 changes.
;=> nil

(r/getv n1*3+n2)
;=> 16


(def sum>10 (->> sum
                 (r/changes #(when (> % 10) "ALARM!"))
                 (r/react-with #(println %))))
;=> sum>10 is an event source. whenever sum's value > 10
;   the occurence containing "ALARM!" is printed.
```
       
Further examples:

[Swing animation example](src/reactor/swing_sample.clj).


API
---
See also [core.clj](src/reactor/core.clj).

### Factories

**signal** -- Creates a new signal with the given initial value.

**eventsource** -- Creates a new event source.

**time** -- Creates a signal that holds the current time in milliseconds.


### Functions applying to Signals as well as Event Sources

**pass** -- Creates a reactive that handles propagation/processing in a different thread. 


### Functions applying to Event Sources

**raise-event!** -- Creates a new occurence. The event will be propagated to all followers.

**hold** -- Creates a signal from an event source where the event of the last occurence is stored as signal value.

**map** -- Creates a new event sources that transforms every event.

**filter** -- Creates a new event source that applies a predicate to decide whether an occurence is propagated. 

**delay** -- Creates a new event source that propagates occurences from a given event source with a delay.

**calm** -- Creates a new event source that propagates occurences from a given event source with a delay. If an occurence arrives while the delay is active the previous occurence is omitted. 

**merge** -- Creates a new event source that emits an occurence whenever one of its source event sources emits an occurence.

**switch** -- Creates a signal from an event source that follows signals that were delivered as event. 

**reduce** -- Creates a signal from an event source that changes its value by applying a function to the current value of the signal and an event. 

**snapshot** -- Creates a signal that takes a value from another signal whenever an event occurs.

**react-with** -- Subscribes a listener function to an event source.


### Functions applying to Signals

**as-signal** -- Takes an arbitraty value. If it's a signal, returns it unchanged. If it's an event source returns a new signal that holds the last event. Otherwise returns a new signal with the given value.

**setv!** -- Sets the value of one signal. If the old value and the new value are different the value is propagated to followers of the signal.

**setvs!** -- Sets values for some signals, values are propagated if respective old and new values differ.

**getv** -- Returns the current value of the signal.

**changes** -- Creates a new event source from a signal that emits an occurence everytime the value is changed. The new value is taken as event.

**lift*** -- Creates a new signal that applies a function to the values of all signals whenever a value changes. The new signal stores the result of the function application.

**lift** -- Macro that takes an s-expr, lifts it (and all subexpressions) and returns a signal that changes whenever a value of the signals of the s-expr changes.

**bind!** -- Connects input with output signals. On every value change a function is applied to current input values. The resulting values are set as value to the output signals.

**process-with** -- Subscribes a listener function to a signal that is invoked upon value changes.

**stop-timer** -- Stop the timer executor associated with a time signal.

       
Current state
-------------
This library is currently purely experimental stuff.
The goal for now is to produce an API that supports the formulation of FRP-based solutions.
Second step is to provide some more elaborate samples that demonstrate how non-trivial
applications like GUI, message processing, animation or games would be described with FRP.
If those samples provide evidence that FRP really simplifies the program code in certain 
areas, the implementation "under the hood" would be improved.

The current implementation is simple, it is just enough to make the API working.
It uses atoms for signal values. The dependencies among reactives form an explicit graph.
Updates are made via a push-based approach, but there is no avoidance of inconsistencies 
(a.k.a glitches).
Event and signal processing can be passed across threads and therefore allows for chains
with asynchronity.

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
