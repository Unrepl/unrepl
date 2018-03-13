(clojure.core/let [nop (clojure.core/constantly nil)
      e (clojure.core/atom (if (clojure.core/find-ns 'unrepl.repl) nop eval))]
  (clojure.main/repl
    :read #(let [x (clojure.core/read)] (clojure.core/case x <<<FIN %2 x))
    :prompt nop
    :eval #(@e %)
    :print nop
    :caught #(do (set! *e %) (reset! e nop) (prn [:unrepl.upgrade/failed %]))))
<BLOB-PAYLOAD>
<<<FIN
(clojure.core/ns user)
(unrepl.repl/start (read))
