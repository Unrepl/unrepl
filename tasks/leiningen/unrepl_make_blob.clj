(ns leiningen.unrepl-make-blob
  (:require [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.string :as str]))

(defn- strip-spaces-and-comments [s]
  #_(I had this nice #"(?s)(?:\s|;[^\n\r]*)+|((?:[^;\"\\\s]|\\.|\"(?:[^\"\\]|\\.)*\")+)"
      but it generates stack overflows...
      so let's write the state machine!)
  (let [sb (StringBuilder.)]
    (letfn [(regular [c]
              (case c
                \; comment
                \# dispatch
                \" (do (.append sb c) string)
                \\ (do (.append sb c) regular-esc)
                (\newline \return \tab \space \,) strip
                (do (.append sb c) regular)))
            (strip [c]
              (case c
                (\newline \return \tab \space \,) strip
                (do (.append sb " ") (regular c))))
            (dispatch [c]
              (case c
                \! comment
                \" (do (.append sb "#\"") string)
                (do (-> sb (.append "#") (.append c)) regular)))
            (comment [c]
              (case c
                \newline strip
                comment))
            (string [c]
              (.append sb c)
              (case c
                \" regular
                \\ string-esc
                string))
            (string-esc [c]
              (.append sb c)
              string)
            (regular-esc [c]
              (.append sb c)
              string)]
      (reduce
        #(%1 %2)
        regular s))
    (str sb)))

(defn- gen-blob [^String code session-actions]
  (-> "resources/unrepl" java.io.File. .mkdirs)
  (let [code (strip-spaces-and-comments (.replace code "#_ext-session-actions{}" session-actions))]
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
          (spit target (str (strip-spaces-and-comments (gen-blob code session-actions)) "\n")))
        (println "The arguments must be: a target file name and an EDN map.")))))

