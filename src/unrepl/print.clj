(ns unrepl.print
  (:require [clojure.string :as str]))

(def ^:dynamic *elide* (constantly nil))

(defprotocol PartialWriter
  (write [pf s])
  (elide [pf x]))

(declare print-on)

(defn print-v [w v rem-depth]
  (print-on w v rem-depth))

(defn print-kv [w [k v] rem-depth]
  (print-on w k rem-depth)
  (write w " ")
  (print-on w v rem-depth))

(defn elide-vs [w vs]
  (elide w vs))

(defn elide-kvs [w kvs]
  (elide w kvs)
  (write w " #ednrepl/... nil"))

(defn print-vs 
  ([w vs rem-depth]
    (print-vs w vs rem-depth print-v elide-vs " "))
  ([w vs rem-depth print-v elide-vs sep]
    (if-some [[v & vs :as v+vs] (seq vs)]
      (if (pos? rem-depth)
        (do 
          (print-v w v rem-depth)
          (loop [rem-length (dec (or *print-length* 10)) vs vs]
            (when-some [[v & vs :as v+vs] vs]
              (write w sep)
              (if (pos? rem-length)
                 (do
                   (print-v w v rem-depth)
                   (recur (dec rem-length) vs))
                 (elide-vs w v+vs)))))
        (elide-vs w v+vs)))))

(defn print-kvs [w vs rem-depth]
  (print-vs w vs rem-depth print-kv elide-kvs ", "))

(def atomic? (some-fn nil? true? false? char? string? symbol? keyword? #(and (number? %) (not (ratio? %)))))

(def ednsafe? "Shallow test of edn safety."
  (some-fn tagged-literal? seq? vector? map? set? atomic?))

(defn unrepl-object [x rep]
  (tagged-literal 'unrepl/object [(class x) (format "0x%x" (System/identityHashCode x)) rep]))

(defn- as-str
  "Like pr-str but escapes all ASCII conytrol chars."
  [x]
  ;hacky
  (cond
    (string? x) (str/replace (pr-str x) #"\p{Cntrl}"
                  #(format "\\u%04x" (int (.charAt ^String % 0))))
    (char? x) (str/replace (pr-str x) #"\p{Cntrl}"
                #(format "u%04x" (int (.charAt ^String % 0))))
    :else (pr-str x)))

(defn ednize "Shallow conversion to edn." [x]
  (cond
    (ratio? x) (tagged-literal 'unrepl/ratio [(.numerator x) (.denominator x)])
    (instance? Throwable x) (tagged-literal 'error (Throwable->map x))
    (class? x) (tagged-literal 'unrepl.java/class (if (.isArray x) [(-> x .getComponentType ednize :form)] (symbol (.getName x))))
    (instance? clojure.lang.IDeref x)
    (let [pending? (and (instance? clojure.lang.IPending x) ; borrowed from https://github.com/brandonbloom/fipp/blob/8df75707e355c1a8eae5511b7d73c1b782f57293/src/fipp/ednize.clj#L37-L51
                     (not (.isRealized ^clojure.lang.IPending x)))
          [ex val] (when-not pending?
                     (try [false @x]
                       (catch Throwable e
                         [true e])))
          failed? (or ex (and (instance? clojure.lang.Agent x)
                           (agent-error x)))
          status (cond
                   failed? :failed
                   pending? :pending
                   :else :ready)]
      (unrepl-object x {:unrepl.ref/status status :unrepl.ref/val val}))
    (-> x class .isArray) (unrepl-object x (seq x))
    (instance? clojure.lang.Namespace x) (tagged-literal 'unrepl/ns (ns-name x))
    :else (unrepl-object x (str x))))

(def ^:dynamic *tagged-literal-printers* {})
(defn default-tagged-literal-printer [w lit p]
  (do (write w (str (str "#" (:tag lit) " "))) (p (:form lit))))

(defn print-on [w x rem-depth]
  (let [rem-depth (dec rem-depth)
        p (fn p [x]
            (cond
              (tagged-literal? x) ((*tagged-literal-printers* (:tag x) default-tagged-literal-printer) w x p)
              (and *print-meta* (meta x)) (recur (tagged-literal 'unrepl/meta [(meta x) (with-meta x nil)])) ; TODO with-meta may throw
              (map? x) (doto w (write "{") (print-kvs x rem-depth) (write "}"))
              (vector? x) (doto w (write "[") (print-vs x rem-depth) (write "]"))
              (seq? x) (doto w (write "(") (print-vs x rem-depth) (write ")"))
              (set? x) (doto w (write "#{") (print-vs x rem-depth) (write "}"))
              (atomic? x) (write w (as-str x))
              :else (recur (ednize x))))]
    (p x)))

(defn- unbound-print-on [w x]
  (binding [*print-length* Long/MAX_VALUE]
    (print-on w x Long/MAX_VALUE)))

(defn edn-str [x]
  (let [out (java.io.StringWriter.)
        w (reify PartialWriter
            (write [_ s] (.write out s))
            (elide [w x] (.write out "#ednrepl/... ")
              (unbound-print-on w (*elide* x))))]
    (binding [*print-readably* true]
      (print-on w x (or *print-level* 4))
      (str out))))