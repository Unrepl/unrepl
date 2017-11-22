(ns leiningen.unrepl-make-blob
  (:require [clojure.java.io :as io]
    [clojure.edn :as edn]))

(defn- gen-blob [^String code session-actions-map]
  (-> "resources/unrepl" java.io.File. .mkdirs)
  (let [code (.replace code "#_ext-session-actions" (pr-str session-actions-map))]
    (prn-str
      `(let [prefix# (name (gensym))
             code# (.replaceAll ~code "(?<!:)unrepl\\.(?:repl|print)" (str "$0" prefix#))
             rdr# (-> code# java.io.StringReader. clojure.lang.LineNumberingPushbackReader.)]
         (try
           (binding [*ns* *ns*
                     *default-data-reader-fn* tagged-literal]
             (loop [ret# nil]
               (let [form# (read rdr# false 'eof#)]
                 (if (= 'eof# form#)
                   ret#
                   (recur (eval form#))))))
           (catch Throwable t#
             (println "[:unrepl.upgrade/failed]")
             (throw t#)))))))

(defn unrepl-make-blob
  ([project] (unrepl-make-blob project "resources/unrepl/blob.clj" "{}"))
  ([project target session-actions]
    (let [session-actions-map (edn/read-string {:default tagged-literal} session-actions)
          code (str (slurp "src/unrepl/print.clj") (slurp "src/unrepl/repl.clj") "\n(ns user)\n(unrepl.repl/start)")]
      (if (map? session-actions-map)
        (let [session-actions-map (into session-actions-map
                                    (map (fn [[k v]]
                                           [k (if (and (seq? v) (symbol? (first v)) (namespace (first v)))
                                                (list 'unrepl.repl/ensure-ns v)
                                                v)]))
                                    session-actions-map)]
          (spit target (gen-blob code session-actions-map)))
        (println "The arguments must be: a target file name and an EDN map.")))))

