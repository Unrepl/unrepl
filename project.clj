(defproject net.cgrand/unrepl "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.nrepl "0.2.12"]]
  :plugins [[lein-cljfmt "0.5.7"]]
  :profiles {:dev {:dependencies [[com.taoensso/timbre "4.8.0"]]}}
  :prep-tasks ["unrepl-make-blob" "javac" "compile"])
