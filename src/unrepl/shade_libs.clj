(ns unrepl.shade-libs
  (:require [clojure.tools.namespace.parse :as nsp]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.pprint :as pp]))

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

(defn- libspec? ; taken from clojure
  "Returns true if x is a libspec"
  [x]
  (or (symbol? x)
      (and (vector? x)
           (or
            (nil? (second x))
            (keyword? (second x))))))

(defn unfold-ns [ns-form]
  (map
    (fn [x]
      (case (when (sequential? x) (first x))
        (:require :use)
        (let [[op & flags] (filter keyword? x)
              libspecs-or-prefixlists (remove keyword? x)]
          (concat
            [op] 
            (mapcat (fn [libspec-or-prefixlist]
                      (if (libspec? libspec-or-prefixlist)
                        [libspec-or-prefixlist]
                        (let [[prefix & libspecs] libspec-or-prefixlist]
                          (for [libspec libspecs]
                            (if (symbol? libspec)
                              (symbol (str prefix "." libspec))
                              (assoc libspec 0 (symbol (str prefix "." (libspec 0)))))))))
              libspecs-or-prefixlists)
            flags))
        :import (let [[op & classes-or-lists] x]
                  (cons op
                    (mapcat
                      (fn [class-or-list]
                        (if (symbol? class-or-list)
                          [class-or-list]
                          (let [[prefix & classes] class-or-list]
                            (for [class classes]
                              (symbol (str prefix "." class))))))
                      classes-or-lists)))
        x))
    ns-form))

(defn slurp-ns [ns-name]
  (let [r (-> ns-name ns-reader clojure.lang.LineNumberingPushbackReader.)
        w (java.io.StringWriter.)
        ns-form (read r)]
    (when-not (and (seq? ns-form) (= 'ns (first ns-form)))
      (throw (ex-info (str "Unexpected first form for ns " ns-name)
               {:ns ns-name :form ns-form})))
    (binding [*out* w] (pp/pprint (unfold-ns ns-form)))
    (io/copy r w)
    (str w)))

(defn make-pattern [nses]
  (->> nses (map name) (sort-by (comp - count))
    (map #(java.util.regex.Pattern/quote %))
    (str/join "|")
    re-pattern))

(defn shade-code [src shaded-nses]
  (str/replace src (make-pattern (keys shaded-nses)) #(name (shaded-nses (symbol %)))))

(defn shade
  "Shade all namespaces (transitively) required by ns-name.
   Shaded code is written using the writer function: a function of two arguments:
   the shaded ns name (a symbol), the shaded code (a string).
   Exceptions to shading are specified under the :except option, but are still written out.
   Provided namespaces won't be shaded nor included.
   This option can be a regex, a symbol, a map (for explicit renames), or a
   collection of such exceptions.
   The default exceptions are empty and provided is #\"clojure\\..*\", don't forget to reassert that if
   you specify your own provided libs."
  [ns-name {:keys [writer except provided] :or {provided #"clojure\..*"}}]
  (letfn [(rename
            ([ns-name] (rename ns-name except))
            ([ns-name except]
              (cond
                (nil? except) nil
                (or (map? except) (set? except)) (except ns-name)
                (symbol? except) (when (= except ns-name) ns-name)
                (instance? java.util.regex.Pattern except) (when (re-matches except (name ns-name)) ns-name)
                (coll? except) (some #(rename ns-name %) except)
                :else (throw (ex-info (str "Unexpected shading exception rule: " except) {:except except})))))
          (provided-alias [ns-name] (rename ns-name provided))
          (shade [shaded-nses ns-name]
            (cond
              (shaded-nses ns-name)
              shaded-nses
              (provided-alias ns-name)
              (assoc shaded-nses ns-name (provided-alias ns-name))
              :else
              (let [shaded-nses (reduce shade shaded-nses (deps ns-name))
                    shaded-nses (assoc shaded-nses ns-name ns-name) ; temporary map the current name to itself to prevent rewrite
                    almost-shaded-code (shade-code (slurp-ns ns-name) shaded-nses)
                    h64 (hash64 almost-shaded-code)
                    shaded-ns-name (or (rename ns-name) (symbol (str ns-name "$" h64)))
                    preserve-shaded-nses (assoc (zipmap (vals shaded-nses) (vals shaded-nses))
                                           ns-name shaded-ns-name) ; avoid rewriting already rewritten nses
                    shaded-code (shade-code almost-shaded-code preserve-shaded-nses)]
                (writer shaded-ns-name shaded-code)
                (assoc shaded-nses ns-name shaded-ns-name))))]
   (shade {} ns-name)))

(defn shade-to-dir
  ([ns-name dir] (shade-to-dir ns-name dir {}))
  ([ns-name dir options]
    (shade ns-name
      (assoc options
        :writer
        (fn [ns-name code]
          (let [filename (str (str/replace (name ns-name) #"[.-]" {"." "/" "-" "_"}) ".clj")
                file (java.io.File. dir filename)]
            (-> file .getParentFile .mkdirs)
            (spit file code :encoding "UTF-8"))))))
  ([ns-name dir optk optv & {:as options}] (shade-to-dir ns-name dir (assoc options optk optv))))

(defn -main
  ([ns-name]
    (-main ns-name "resources"))
  ([ns-name dir]
    (shade-to-dir (symbol ns-name) dir)))
