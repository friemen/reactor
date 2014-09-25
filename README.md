reactor
=======

Exploring reactive programming with Clojure.

[![Build Status](https://travis-ci.org/friemen/reactor.png?branch=master)](https://travis-ci.org/friemen/reactor)

The purpose of the factories and combinators as implemented here 
is to enable declarative specifications of eventstream and behavior
processing chains (using the ->> macro and familiar functions
like filter, map or merge).

To use it add the following dependency to your project.clj

```clojure
[reactor "0.7.0"]
```

The API is still subject to change.


Concepts
--------

An *event* is something non-continuous that "happens", represented by a value.

An *occurence* is a pair of value and timestamp.

An *eventstream* is an observable stream of occurences. 

A *behavior* is an observable variable (i.e. it changes over time).

A *reactive* is an abstraction of eventstream and behavior. 


Examples
--------
TODO

See [reactor-samples project](https://github.com/friemen/reactor-samples).


References
----------

E.Amsden - [A Survey of Functional Reactive Programming](http://www.cs.rit.edu/~eca7215/frp-independent-study/Survey.pdf)

A.Courtney - [Frappe: Functional Reactive Programming in Java](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/frappe-padl01.pdf)

A.Courtney, C.Elliot - [Genuinely Functional User Interfaces](http://haskell.cs.yale.edu/wp-content/uploads/2011/02/genuinely-functional-guis.pdf)

E.Czaplicki - [ELM](http://elm-lang.org/papers/concurrent-frp.pdf)

C.Elliot, P.Hudak - [Functional Reactive Animation](http://conal.net/papers/icfp97/icfp97.pdf)

C.Elliot - [Push-pull functional reactive programming](http://conal.net/papers/push-pull-frp/push-pull-frp.pdf)

I.Maier, T.Rompf, M.Odersky - [Deprecating the Observer Pattern](http://lamp.epfl.ch/~imaier/pub/DeprecatingObserversTR2010.pdf)

L.Meyerovich - [Flapjax: Functional Reactive Web Programming](http://www.cs.brown.edu/research/pubs/theses/ugrad/2007/lmeyerov.pdf)


License
=======

Copyright 2014 F.Riemenschneider

Distributed under the Eclipse Public License, the same as Clojure.
