(ns leiningen.unrepl-make-blob
  (:require [clojure.java.io :as io]))

(defn unrepl-make-blob [project]
  (let [code (str (slurp "src/unrepl/print.clj") (slurp "src/unrepl/repl.clj") "\n(unrepl.repl/start)")]
    (-> "resources/unrepl" java.io.File. .mkdirs)
    (spit "resources/unrepl/blob.clj"
      (prn-str
        `(let [prefix# (name (gensym))
             code# (.replaceAll ~code "(?<!:)unrepl\\.(?:repl|print)" (str "$0" prefix#))
             rdr# (-> code# java.io.StringReader. clojure.lang.LineNumberingPushbackReader.)]
         (try
           (binding [*ns* *ns*]
             (loop [ret# nil]
               (let [form# (read rdr# false 'eof#)]
                 (if (= 'eof# form#)
                   ret#
                   (recur (eval form#))))))
           (catch Throwable t#
             (println "[:unrepl.upgrade/failed]")
             (throw t#))))))))

