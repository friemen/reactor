# reactor

A tool for reactive programming with Clojure.

[![Build Status](https://travis-ci.org/friemen/reactor.png?branch=master)](https://travis-ci.org/friemen/reactor)

[![Clojars Project](http://clojars.org/reactor/latest-version.svg)](http://clojars.org/reactor)

[API docs](http://friemen.github.com/reactor)

The purpose of the factories and combinators as implemented here 
is to support declarative specifications of eventstream and behavior
processing chains using the `->>` macro and familiar functions
like `filter`, `map` or `merge`.

Status of this library: it already offers many combinators that you
can find in other libraries for reactive programming. So it's likely
that you can use it right now to build interesting solutions. However,
because this whole subject is complex and my implementation is young,
I would not recommend to use it for anything else than educational
purposes.  I'm currently in the process of improving tests and
creating more complex scenarios, which will help to make it more
mature.


## Getting started

To use it you need a dependency declaration in your project.clj, see above.

In any namespace issue

```clojure
(require '[reactor.core :as r])
```

or put it into the namespace declaration like so:

```clojure
(ns my.own.namespace
  (:require [reactor.core :as r]))
```

Now you have reactor functions available with `r/` prefix.


### Using a timer

```clojure
(def timer (r/timer 2000))
```

The value of `timer` is a *behavior* (think of an observable,
thread-safe variable) that increases a counter every 2
seconds, starting with 1.

Let's do something with it, for example multiply the value by 10 and
print the result to the console.

```clojure
(->> timer
     (r/map (partial * 10))
     (r/subscribe println))
```

You should see a sequence of numbers printed (because the timer is a
background task, the numbers may not show up in the REPL output, so
you must look at the console output).

You can clear the *network* and all associated schedulers/timers
all-at-once by issuing:

```clojure
(r/reset-network!)
```

The timer stops and the network is empty.


### What is this network thing?

Reactor is built upon
[reactnet](https://github.com/friemen/reactnet). Reactnet maintains an
explicit graph that connects behaviors and eventstreams with
functions, called the *network*.  Everytime you use one of the `r/`
functions you'll likely change the configuration of this network.

The network is not static. It can be changed anytime, either by
reactor API functions, or by results of functions used by `r/flatmap`
or eventstreams used with `r/switch`. It also changes when an
eventstream *completes*, i.e. it is guaranteed to not have any more
pending events.

In addition, each network has an associated set of schedulers, for
example to create delays or throttling or providing timer ticks.

You can easily create distinct networks:

```clojure
(def n (r/network "periodic-actions"))
```

The advantage of creating distinct networks is more control and better
performance.  To refer to a specific network when using reactor API
you use the `(r/with network & exprs)` macro, which creates a dynamic
var binding for `reactnet.core/*netref*` to `n`:

```clojure
(r/with n
        (def timer (r/timer 2000))
		(->> timer
			 (r/map (partial * 10))
		     (r/subscribe println)))
```

Of course, you can clear this specific network using:
```clojure
(r/reset-network! n)
```

### Building up processing chains and pushing values into it

TODO show an example with periodic invocation of a service (r/sample),
scanning the result (r/scan) and doing something when some condition
applies (r/filter).


### Pushing values into a eventstream or behavior

TODO explain how to push values into the right network


### Asynchronity on-demand


TODO explain how to put function invocation to different threads.


## More Examples

See [reactor-samples project](https://github.com/friemen/reactor-samples).



# License

Copyright 2014 F.Riemenschneider

Distributed under the Eclipse Public License, the same as Clojure.
