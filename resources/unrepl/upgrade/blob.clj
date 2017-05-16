(do
  (clojure.core/require 'clojure.main)
  (clojure.core/require 'clojure.java.io)
  (clojure.core/let
    [star-ns clojure.core/*ns* star-1 clojure.core/*1 star-2 clojure.core/*2 star-3 clojure.core/*3
     required-nses '[unrepl.print unrepl.repl]
     state (clojure.core/atom :ready)]
    (clojure.main/repl
      :prompt (fn []
                (clojure.core/when (clojure.core/= :ready @state)
                  (clojure.core/if-some [ns (clojure.core/first (clojure.core/remove find-ns required-nses))]
                    (do
                      (clojure.core/prn [:unrepl.upgrade/require ns :unrepl.upgrade/end-of-ns])
                      (clojure.core/reset! state :loading))
                    (clojure.core/reset! state :done))))
      :read (clojure.core/fn [request-prompt request-exit]
              (if (clojure.core/= :loading @state)
                (clojure.core/let [x (clojure.main/repl-read request-prompt request-exit)]
                  (if (clojure.core/= x :unrepl.upgrade/end-of-ns)
                    (do
                      (reset! state :ready)
                      request-prompt)
                    x))
                request-exit))
      :caught (clojure.core/fn [e]
                (clojure.core/prn [:unrepl.upgrade/failed (clojure.core/pr-str e)])
                (clojure.core/reset! state :failed))
      :print (clojure.core/constantly nil))
    (set! clojure.core/*ns* star-ns)
    (set! clojure.core/*1 star-1)
    (set! clojure.core/*2 star-2)
    (set! clojure.core/*3 star-3)
    (clojure.core/when (= @state :done)
      (clojure.core/eval '(unrepl.repl/start)))))
