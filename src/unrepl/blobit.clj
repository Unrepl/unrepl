(ns unrepl.blobit
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
                (\newline \return) strip-nl
                (\tab \space \,) strip
                (do (.append sb c) regular)))
            (strip [c]
              (case c
                (\newline \return) strip-nl
                (\tab \space \,) strip
                \; comment
                (do (.append sb " ") (regular c))))
            (strip-nl [c]
              (case c
                (\newline \return \tab \space \,) strip-nl
                \; comment
                (do (.append sb "\n") (regular c))))
            (dispatch [c]
              (case c
                \! comment
                \" (do (.append sb "#\"") string)
                (do (-> sb (.append "#") (.append c)) regular)))
            (comment [c]
              (case c
                \newline strip-nl
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
  (let [template (slurp (io/resource "unrepl/blob-template.clj"))
        code (.replace code "#_ext-session-actions{}" session-actions)
        code (str/replace template "<BLOB-PAYLOAD>" code)
        code (strip-spaces-and-comments code)
        suffix (str "$" (-> code (.getBytes "UTF-8") sha1 java.io.ByteArrayInputStream. base64-encode))]
    (str (str/replace code #"(?<!:)unrepl\.(?:repl|print)" (fn [x] (str x suffix))) "\n"))) ; newline to force eval by the repl

(defn -main
  ([] (-main "resources/unrepl/blob.clj" "{}"))
  ([target session-actions]
    (-> target io/file .getParentFile .mkdirs)
    (let [session-actions-source (if (re-find #"^\s*\{" session-actions) session-actions (slurp session-actions))
          session-actions-map (edn/read-string {:default (fn [tag data] (tagged-literal 'unrepl-make-blob-unquote (list 'tagged-literal (tagged-literal 'unrepl-make-blob-quote tag) data)))} session-actions-source)
          code (str (slurp (io/resource "unrepl/print.clj")) (slurp (io/resource "unrepl/repl.clj")))]
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

