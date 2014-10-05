(defproject reactor "0.7.1"
  :description "Reactive programming with eventstreams and behaviors"
  :url "https://github.com/friemen/reactor"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [reactnet "0.7.0"]]
  :plugins [[codox "0.8.10"]]
  :codox {:defaults {}
          :sources ["src"]
          :exclude []
          :src-dir-uri "https://github.com/friemen/reactor/blob/master/"
          :src-linenum-anchor-prefix "L"}  
  :scm {:name "git"
        :url "https://github.com/friemen/reactor"}
  :repositories [["clojars" {:url "https://clojars.org/repo"
                             :creds :gpg}]])
