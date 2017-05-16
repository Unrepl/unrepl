(ns unrepl.upgrade)

(defn- resource [ns]
  (.getResource (.getContextClassLoader (Thread/currentThread))
    (str (.replace (name ns) "." "/") ".clj")))

(defn upgrade!
  "Upgrades a repl to unrepl. Upon success the first form read on the input stream should be
   a :unrepl/hello message.
   in is the inputstream from the repl (so its *out*)
   out is the outputstream to the repl (so its *in*)"
  [in out]
  (binding [*in* in
            *out* out]
    (-> "unrepl.upgrade.blob" resource slurp println)
    (loop []
      (let [x (read)]
        (case (when (vector? x) (first x))
          :unrepl.upgrade/require
          (let [[_ ns eom] x]
            (-> ns resource slurp println)
            (println eom))
          :unrepl.upgrade/success true
          :unrepl.upgrade/failed (throw (ex-info "Can't upgrade." {:reason (second x)}))
          (recur))))))
