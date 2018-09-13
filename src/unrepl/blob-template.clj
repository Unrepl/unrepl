(clojure.core/let [nop (clojure.core/constantly nil)
                   done (promise)
                   e (clojure.core/atom eval)]
  (-> (create-ns 'sym)
    (intern '-init-done)
    (alter-var-root
      (fn [v]
        (if (instance? clojure.lang.IDeref v)
          (do ; another thread created the var, wait for it to be finished
            (reset! e (if-some [ex @v]
                        (fn [_] (throw ex))
                        nop))
            v)
          done))))
  (clojure.main/repl
    :read #(let [x (clojure.core/read)] (clojure.core/case x <<<FIN (do (deliver done nil) %2) x))
    :prompt nop
    :eval #(@e %)
    :print nop
    :caught #(do (set! *e %) (reset! e nop) (prn [:unrepl.upgrade/failed %]))))
<BLOB-PAYLOAD>
<<<FIN
(clojure.core/ns user)
(unrepl.repl/start (clojure.edn/read {:default tagged-literal} *in*))
