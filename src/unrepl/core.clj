(ns unrepl.core
  (:require [clojure.main :as m]
    [unrepl.print :as p]))

(defn tagging-writer [tag write]
  (proxy [java.io.Writer] []
    (close []) ; do not cascade
    (flush []) ; atomic always flush
    (write
      ([x]
        (write [tag (cond 
                      (string? x) x
                      (integer? x) (str (char x))
                      :else (String. ^chars x))]))
      ([string-or-chars off len]
        (when (pos? len)
          (write [tag (subs (if (string? string-or-chars) string-or-chars (String. ^chars string-or-chars))
                        off (+ off len))]))))))

(defn atomic-write [^java.io.Writer w]
  (fn [x]
    (let [s (p/edn-str x)] ; was pr-str, must occur outside of the locking form to avoid deadlocks
      (locking w
        (.write w s)
        (.flush w)))))

(defn pre-reader [^java.io.Reader r before-read]
  (proxy [java.io.FilterReader] [r]
    (read 
      ([] (before-read) (.read r))
      ([cbuf] (before-read) (.read r cbuf))
      ([cbuf off len] (before-read) (.read r cbuf off len)))))

(defn start-repl
  ([] (start-repl 'exit))
  ([safeword]
    (with-local-vars [reading false] 
     (let [raw-out *out*
           write (atomic-write raw-out)
           edn-out (java.io.BufferedWriter. (tagging-writer :out write) 512)
           unrepl (atom false)
           ensure-unrepl (fn []
                           (when-not @unrepl
                             (reset! unrepl true)
                             (flush)
                             (set! *out* edn-out)
                             (write [:unrepl/hello {:command {}}])))
           ensure-raw-repl (fn []
                             (when (and (not @reading) @unrepl) ; reading from eval!
                               (reset! unrepl false)
                               (write [:upgrade nil])
                               (flush)
                               (set! *out* raw-out)))]
       (binding [*out* raw-out
                 *err* (tagging-writer :err write)
                 *in* (-> *in* (pre-reader ensure-raw-repl) clojure.lang.LineNumberingPushbackReader.)]
         (m/repl
           :prompt (fn []
                     (ensure-unrepl)
                     (write [:prompt {:ns *ns* :*warn-on-reflection* *warn-on-reflection*}])) ; not to spec
           :read (fn [request-prompt request-exit]
                   (with-bindings {reading true}
                     (let [r (m/repl-read request-prompt request-exit)]
                       ({safeword request-exit} r r))))
           :eval eval
           :print (fn [x]
                    (ensure-unrepl)
                    (write [:eval x]))
           :caught (fn [e]
                     (ensure-unrepl)
                     (write [:exception e]))))))))