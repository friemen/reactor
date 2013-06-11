(defproject reactor "0.1.0-SNAPSHOT"
  :description "Functional reactive programming in Clojure"
  :url "https://github.com/friemen/reactor"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]]
  :plugins [[codox/codox.leiningen "0.6.4"]]
  :repl-options {:port 9090}
  :codox {:exclude [reactor.swing-sample]})
