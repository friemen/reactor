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
because this whole subject is complex and my implementation is young
(in other words: you may encounter bugs), I would not recommend to use
it for anything else than educational purposes.  I'm currently in the
process of improving tests and creating more complex scenarios, which
will help to make it more mature.


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

Let's do something with it, for example multiply the tick value by 10
and print the result to the console.

```clojure
(->> timer
     (r/map (partial * 10))
     (r/subscribe println))
```

You should see a sequence of numbers printed. Because the timer is a
background task, the numbers may not show up in the REPL output, so
you must look at the console output.

You can clear the *network* and all associated schedulers/timers
by issuing:

```clojure
(r/reset-network!)
```

The timer stops and the network is empty.


### What is this 'network' thing?

Reactor is built upon
[reactnet](https://github.com/friemen/reactnet). Reactnet maintains an
explicit graph that connects *reactives* (a generalization of
behaviors and eventstreams) with functions. This is called the *network*.
Everytime you use one of the `r/` functions you'll likely change the
configuration of this network.

The network is not static. It can be changed anytime, either by
reactor API functions, or by results of functions used by `r/flatmap`
or eventstreams used with `r/switch`, `r/amb` and others. It also
changes when an eventstream *completes*, which means the stream is
guaranteed to not have any more pending events. 

In addition, each network has an associated set of schedulers, for
example to create delays or throttling or providing timer ticks.

You can easily create distinct networks:

```clojure
(def n (r/network "periodic-actions"))
```

The advantage of creating distinct networks is more control and better
performance.  To refer to a specific network when using reactor API
you use the `(r/with network exprs)` macro, which creates a dynamic
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

### Building up processing chains

Suppose you want to query a stock price every second. In case the
price increases three times in a row you want to send a mail.

Let's define a function that mimicks the remote call to get the
latest stock price:

```clojure
(defn stock-price
  []
  (rand-nth (range 1 10)))
```

We'll need some helper functions, the first creates a reduction
function to buffer items, the second is a predicate yielding true if
all numbers in xs are monotonically increasing, the third is the dummy
action implementation.

```clojure
(defn sliding-buffer
  [n]
  (fn [buf x]
    (conj (vec (drop (- (count buf) (dec n)) buf)) x)))

(defn increasing?
  [xs]
  (->> xs
       (map vector (drop 1 xs))
       (every? (partial apply >))))

(defn send-mail!
  [prices]
  (println "Increasing prices!" prices))
```

Again, we create a network instance:

```clojure
(def n (r/network "price-checker"))
```

We connect these by the following expression:


```clojure
(r/with n (->> (r/sample 1000 stock-price)
               (r/scan (sliding-buffer 3) [])
               (r/filter #(>= (count %) 3))
               (r/filter increasing?)
               (r/subscribe send-mail!)))
```

`(r/sample millis f)` invokes the given no-arg function `f` every
`millis` milliseconds and emits the result.

`(r/scan f initial-value r)` applies reduction function `f` to latest
result (or `initial-value`) and the latest items from `r`. Each result
is emitted.

The resulting chain of function applications may look unfamiliar,
however it's pretty declarative.


### Asynchronity on-demand

By default, reactor propagates values synchronously through the
network (ordered by topological levels), doing as much as possible in
a single function call. Functions that are either expensive to execute
or must wait for IO or a remote service would block the propagation
within a network. In the example above querying the stock price and
sending mail are actions that you might want to execute
asynchronously.

For those cases you can wrap the function into an `(r/in-future ...)`
expression. Most of reactors combinators will then pass the function
to a future-based executor. The result of the function application
will be pushed into the network just like an external stimulus.

Here's the example from above with explicit asynchronity:

```clojure
(r/with n (->> (r/sample 1000 (r/in-future stock-price))
               (r/scan (sliding-buffer 3) [])
               (r/filter #(>= (count %) 3))
               (r/filter increasing?)
               (r/subscribe (r/in-future send-mail!))))
```


### More on eventstreams and behaviors

The foundation of reactor uses a generalization of eventstream and
behavior, called a *reactive*. You will often find `r` or `rs` as
argument names in reactor functions, usually you can then pass an
eventstream or a behavior, or even mix them in one function
application.

`r/timer` or `r/sample` are two possible ways to create a behavior or
an eventstream, respectively. In general, you can create a general
purpose eventstream with `(r/eventstream)` and a behavior using
`(r/behavior x)`, where `x` is an arbitrary value or even itself a
reactive.

A behavior has always a value, it's much like a thread-safe variable
(some say: "a time-varying value"), so functions *lifted* to work on
behaviors via `r/map` will execute as soon as one behavior changes. An
eventstream, on the other hand, contains a bounded queue, and values
are consumed from it. Therefore an eventstream might not have any
value available.  A *lifted* function working on some eventstreams
will only be executed when all eventstreams have a value
available. You can mix eventstreams and behaviors in the same `r/map`
application.

A behavior emits a value whenever it receives a new value which is
different from its current value. Hence, there is no need to convert a
behavior to an eventstream. To create a behavior from an eventstream
you'll use `r/hold`.

You can push a value to an eventstream or behavior using `(r/push! n r
v)`, where `n` is the associated network, `r` is the reactive and `v`
is an arbitrarily structured value.

You can always `deref` a reactive. A behavior will return its current
value, an eventstream will return the next value or nil. Please note
that `r/push!` is executed asynchronously, therefore you cannot expect
that a immediate `deref` in the same thread will yield the last
recently pushed value.

You can *complete* both of them using `(r/complete! n r)`, which means
they won't accept new values. An eventstream will continue to emit
events that it received before it received the completion request.

### Expression lifting

You can do dataflow programming on behaviors by *lifting*
expressions. An example:

TODO


## More Examples

See [reactor-samples project](https://github.com/friemen/reactor-samples).



# License

Copyright 2014 F.Riemenschneider

Distributed under the Eclipse Public License, the same as Clojure.
