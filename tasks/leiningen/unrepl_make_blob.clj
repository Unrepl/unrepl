(ns leiningen.unrepl-make-blob
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.string :as str]))

(defn- base64-encode
  "Non-standard base64 to avoid name munging"
  [^java.io.InputStream in]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_$"
        sb (StringBuilder.)]
    (loop [shift 4 buf 0]
      (let [got (.read in)]
        (if (neg? got)
          (do
            (when-not (= shift 4)
              (let [n (bit-and (bit-shift-right buf 6) 63)]
                (.append sb (.charAt table n))))
            #_(cond
               (= shift 2) (.append sb "==")
               (= shift 0) (.append sb \=))
            (str sb))
          (let [buf (bit-or buf (bit-shift-left got shift))
                n (bit-and (bit-shift-right buf 6) 63)]
            (.append sb (.charAt table n))
            (let [shift (- shift 2)]
              (if (neg? shift)
                (do
                  (.append sb (.charAt table (bit-and buf 63)))
                  (recur 4 0))
                (recur shift (bit-shift-left buf 6))))))))))

(defn- sha1 [^bytes bytes]
  (.digest (java.security.MessageDigest/getInstance "SHA-1") bytes))

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
              regular)]
      (reduce
        #(%1 %2)
        regular s))
    (str sb)))

(defn- gen-blob [^String code session-actions]
  (-> "resources/unrepl" java.io.File. .mkdirs)
  (let [code (strip-spaces-and-comments (.replace code "#_ext-session-actions{}" session-actions))
        suffix (str "$" (-> code (.getBytes "UTF-8") sha1 java.io.ByteArrayInputStream. base64-encode))
        code (str/replace code #"(?<!:)unrepl\.(?:repl|print)" (fn [x] (str x suffix)))
        main-ns (symbol (str "unrepl.repl" suffix))]
    (prn-str
      `(or (find-ns '~main-ns)
         (let [rdr# (-> ~code java.io.StringReader. clojure.lang.LineNumberingPushbackReader.)]
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

