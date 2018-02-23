(ns leiningen.unrepl-make-blob
  (:require [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.string :as str]))

(defn- gen-blob [^String code session-actions]
  (-> "resources/unrepl" java.io.File. .mkdirs)
  (let [code (.replace code "#_ext-session-actions" session-actions)]
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
             (throw t#)))))))

(defn unrepl-make-blob
  ([project] (unrepl-make-blob project "resources/unrepl/blob.clj" "{}"))
  ([project target session-actions]
    (let [session-actions-source (if (re-find #"^\s*\{" session-actions) session-actions (slurp session-actions))
          session-actions-map (edn/read-string {:default (fn [tag data] (tagged-literal 'unrepl-make-blob-unquote (list 'tagged-literal (tagged-literal 'unrepl-make-blob-quote tag) data)))} session-actions-source)
          code (str (slurp "src/unrepl/print.clj") (slurp "src/unrepl/repl.clj") "\n(ns user)\n(unrepl.repl/start)")]
      (if (map? session-actions-map)
        (let [session-actions-map (into session-actions-map
                                    (map (fn [[k v]]
                                           [k (tagged-literal 'unrepl-make-blob-syntaxquote
                                                (if (and (seq? v) (symbol? (first v)) (namespace (first v)))
                                                  (list 'unrepl.repl/ensure-ns v)
                                                  v))]))
                                    session-actions-map)
              session-actions (-> session-actions-map pr-str 
                                (str/replace #"#unrepl-make-blob-(?:syntax|un)?quote " {"#unrepl-make-blob-syntaxquote " "`"
                                                                                        "#unrepl-make-blob-unquote " "~"
                                                                                        "#unrepl-make-blob-quote " "'"}))]
          (spit target (gen-blob code session-actions)))
        (println "The arguments must be: a target file name and an EDN map.")))))

