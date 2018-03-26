(ns unrepl.shade-libs
  (:require [clojure.tools.namespace.parse :as nsp]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(defn ns-reader [ns-name]
  (let [base (str/replace (name ns-name) #"[.-]" {"." "/" "-" "_"})]
    (some-> (or (io/resource (str base ".clj")) (io/resource (str base ".cljc"))) io/reader)))

(defn deps [ns-name]
  (when-some [rdr (ns-reader ns-name)]
    (with-open [rdr (-> rdr java.io.PushbackReader.)]
      (nsp/deps-from-ns-decl (nsp/read-ns-decl rdr)))))

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

(defn hash64 [s]
  (-> s (.getBytes "UTF-8") sha1 java.io.ByteArrayInputStream. base64-encode))

(defn exception
  [ns-name except]
  (cond
    (or (map? except) (set? except)) (except ns-name)
    (symbol? except) (when (= except ns-name) ns-name)
    (instance? java.util.regex.Pattern except) (when (re-matches except (name ns-name)) ns-name)
    (coll? except) (some #(exception ns-name %) except)
    :else (throw (ex-info (str "Unexpected shading exception rule: " except) {:except except}))))

(defn shade
  "Shade all namespaces (transitively) required by ns-name.
   Shaded code is written using the writer function: a function of two arguments:
   the shaded ns name (a symbol), the shaded code (a string).
   Exceptions to shading are specified under the :except option.
   This option can be a regex, a symbol, a map (for explicit renames), or a
   collection of such exceptions.
   The default exceptions is #\"clojure\\..*\", don't forget to reassert that if
   you specify your own exceptions."
  [ns-name {:keys [writer except] :or {except #"clojure\..*"}}]
  (letfn [(rename [ns-name] (exception ns-name except))
          (shade [shaded-nses ns-name]
            (if (or (shaded-nses ns-name) (rename ns-name))
              shaded-nses
              (let [shaded-nses (reduce shade shaded-nses (deps ns-name))
                    pat (when-some [nses (seq (keys shaded-nses))]
                          (re-pattern (str/join "|" (map #(java.util.regex.Pattern/quote (name %)) nses))))
                    almost-shaded-code (cond-> (slurp (ns-reader ns-name))
                                         pat (str/replace pat #(name (shaded-nses (symbol %)))))
                    h64 (hash64 almost-shaded-code)
                    shaded-ns-name (symbol (str ns-name "$" h64))
                    shaded-code (str/replace almost-shaded-code (name ns-name) (name shaded-ns-name))]
                (writer shaded-ns-name shaded-code)
                (assoc shaded-nses ns-name shaded-ns-name))))]
   (shade {} ns-name)))

(defn shade-to-dir
  [ns-name dir & {:as options}]
  (shade ns-name
    (assoc options
      :writer
      (fn [ns-name code]
        (let [filename (str (str/replace (name ns-name) #"[.-]" {"." "/" "-" "_"}) ".clj")
              file (java.io.File. dir filename)]
          (-> file .getParentFile .mkdirs)
          (spit file code :encoding "UTF-8"))))))

(defn -main
  ([ns-name]
    (-main ns-name "resources"))
  ([ns-name dir]
    (shade-to-dir (symbol ns-name) dir)))
