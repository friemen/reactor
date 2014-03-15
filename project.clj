(defproject reactor "0.5.ALPHA1"
  :description "Functional reactive programming in Clojure"
  :url "https://github.com/friemen/reactor"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/data.priority-map "0.0.4"]]
  :plugins [[codox/codox.leiningen "0.6.4"]]
  :repl-options {:port 9090}
  :scm {:name "git"
        :url "https://github.com/friemen/reactor"}
  :repositories [["clojars" {:url "https://clojars.org/repo"
                             :creds :gpg}]]
  :codox {:exclude [reactor.swing-sample]
          :src-dir-uri "https://github.com/friemen/reactor/blob/master"
          :src-linenum-anchor-prefix "L"})
