(ns unrepl.repl
  (:require [clojure.main :as m]
    [unrepl.print :as p]))

(defn tagging-writer
  ([write]
    (proxy [java.io.Writer] []
      (close []) ; do not cascade
      (flush []) ; atomic always flush
      (write
        ([x]
          (write (cond 
                   (string? x) x
                   (integer? x) (str (char x))
                   :else (String. ^chars x))))
        ([string-or-chars off len]
          (when (pos? len)
            (write (subs (if (string? string-or-chars) string-or-chars (String. ^chars string-or-chars))
                     off (+ off len))))))))
  ([tag write]
    (tagging-writer (fn [s] (write [tag s]))))
  ([tag group-id write]
    (tagging-writer (fn [s] (write [tag s group-id])))))

(defn atomic-write [^java.io.Writer w]
  (fn [x]
    (let [s (p/edn-str x)] ; was pr-str, must occur outside of the locking form to avoid deadlocks
      (locking w
        (.write w s)
        (.write w "\n")
        (.flush w)))))

(defn pre-reader [^java.io.Reader r before-read]
  (proxy [java.io.FilterReader] [r]
    (read 
      ([] (before-read) (.read r))
      ([cbuf] (before-read) (.read r cbuf))
      ([cbuf off len] (before-read) (.read r cbuf off len)))))

(def commands {'set-file-line-col (let [field (when-some [^java.lang.reflect.Field field 
                                                          (->> clojure.lang.LineNumberingPushbackReader
                                                                  .getDeclaredFields
                                                                  (some #(when (= "_columnNumber" (.getName ^java.lang.reflect.Field %)) %)))]
                                                (.setAccessible field true))] ; sigh
                                    (fn [file line col]
                                      (set! *file* file)
                                      (set! *source-path* file)
                                      (.setLineNumber *in* line)
                                      (some-> field (.set *in* col))))})

(defn weak-store [sym not-found]
  (let [ids-to-weakrefs (atom {})
        weakrefs-to-ids (atom {})
        refq (java.lang.ref.ReferenceQueue.)]
    (.start (Thread. (fn []
                       (let [wref (.remove refq)]
                         (let [id (@weakrefs-to-ids wref)]
                           (swap! weakrefs-to-ids dissoc wref)
                           (swap! ids-to-weakrefs dissoc id)))
                           (recur))))
    {:put (fn [xs]
            (let [x (if (nil? xs) () xs)
                  id (gensym)
                  wref (java.lang.ref.WeakReference. xs refq)]
              (swap! weakrefs-to-ids assoc wref id)
              (swap! ids-to-weakrefs assoc id wref)
              {:get (tagged-literal 'unrepl/raw (str "\u0010" (p/full-edn-str (list sym id))))}))
     :get (fn [id]
            (or (some-> @ids-to-weakrefs ^java.lang.ref.WeakReference (get id) .get)
              not-found))}))

(defn- base64-str [^java.io.InputStream in]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        sb (StringBuilder.)]
    (loop [shift 4 buf 0]
      (let [got (.read in)]
        (if (neg? got)
          (do
            (when-not (= shift 4)
              (let [n (bit-and (bit-shift-right buf 6) 63)]
                (.append sb (.charAt table n))))
            (cond
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

(defn start []
  ; TODO: tighten by removing the dep on m/repl
  (with-local-vars [in-eval false
                    command-mode false
                    unrepl false
                    eval-id 0
                    prompt-vars #{#'*ns* #'*warn-on-reflection*}
                    current-eval-future nil]
    (let [current-eval-thread+promise (atom nil)
          elision-store (weak-store '... (tagged-literal 'unrepl/... nil))
          attachment-store (weak-store 'file (constantly nil))
          commands (assoc commands
                     '... (:get elision-store)
                     'file (comp (fn [inf]
                                   (if-some [in (inf)]
                                     (with-open [^java.io.InputStream in in]
                                       (base64-str in))
                                     (tagged-literal 'unrepl/... nil)))
                             (:get attachment-store)))
          CTRL-C \u0003
          CTRL-D \u0004
          CTRL-P \u0010
          CTRL-Z \u001A
          raw-out *out*
          write (atomic-write raw-out)
          edn-out (tagging-writer :out write)
          ensure-unrepl (fn []
                          (var-set command-mode false)
                          (when-not @unrepl
                            (var-set unrepl true)
                            (flush)
                            (set! *out* edn-out)
                            (binding [*print-length* Long/MAX_VALUE
                                      *print-level* Long/MAX_VALUE]
                              (write [:unrepl/hello {:commands {:interrupt (tagged-literal 'unrepl/raw CTRL-C)
                                                                :exit (tagged-literal 'unrepl/raw CTRL-D)
                                                                :background-current-eval
                                                                (tagged-literal 'unrepl/raw CTRL-Z)
                                                                :set-source
                                                                (tagged-literal 'unrepl/raw
                                                                  [CTRL-P
                                                                   (tagged-literal 'unrepl/edn 
                                                                     (list 'set-file-line-col
                                                                       (tagged-literal 'unrepl/param :unrepl/sourcename)
                                                                       (tagged-literal 'unrepl/param :unrepl/line)
                                                                       (tagged-literal 'unrepl/param :unrepl/column)))])}}]))))
          ensure-raw-repl (fn []
                            (when (and @in-eval @unrepl) ; reading from eval!
                              (var-set unrepl false)
                              (write [:bye nil])
                              (flush)
                              (set! *out* raw-out)))
          eval-executor (java.util.concurrent.Executors/newCachedThreadPool)
          interruption (ex-info "Interrupted" {})
          interrupt! (fn []
                       (when-some [[^Thread t p] @current-eval-thread+promise]
                         (reset! current-eval-thread+promise nil)
                         (deliver p {:interrupted true})
                         (when (:interrupted @p)
                           (.stop t)
                           #_(.join t)))) ; seems to block forever, to investigate
          background! (fn []
                        (when-some [[^Thread t p] @current-eval-thread+promise]
                          (deliver p {:value @current-eval-future :bindings {}})))
          interruptible-eval
          (fn [form]
            (let [bindings (get-thread-bindings)
                  p (promise)]
               (var-set current-eval-future
                 (.submit eval-executor
                   ^Callable
                   (fn []
                     (reset! current-eval-thread+promise [(Thread/currentThread) p])
                     (with-bindings bindings
                       (try
                         (let [v (with-bindings {in-eval true} (eval form))]
                           (deliver p {:value v :bindings (get-thread-bindings)})
                           v)
                         (catch Throwable t
                           (deliver p {:caught t :bindings (get-thread-bindings)})
                           (throw t)))))))
               (loop []
                 (or (deref p 40 nil)
                   (let [c (.read *in*)]
                     (cond
                       (or (Character/isWhitespace c) (= \, c)) (recur)
                       (= (int CTRL-C) c) (interrupt!)
                       (= (int CTRL-Z) c) (background!)
                       :else (.unread *in* c)))))
               (let [{:keys [bindings caught value interrupted]} @p]
                 (reset! current-eval-thread+promise nil)
                 (var-set current-eval-future nil)
                 (doseq [[v val] bindings]
                   (var-set v val))
                 (cond
                   interrupted (throw interruption)
                   caught (throw caught)
                   :else value))))]
      (binding [*out* raw-out
                *err* (tagging-writer :err write)
                *in* (-> *in* (pre-reader ensure-raw-repl) clojure.lang.LineNumberingPushbackReader.)
                *file* "unrepl-session"
                *source-path* "unrepl-session"
                p/*elide* (:put elision-store)
                p/*attach* (:put attachment-store)]
        (m/repl
          :prompt (fn []
                    (ensure-unrepl)
                    (write [:prompt (into {:cmd @command-mode}
                                      (map (fn [v]
                                             (let [m (meta v)]
                                               [(symbol (name (ns-name (:ns m))) (name (:name m))) @v])))
                                      @prompt-vars)]))
          :read (fn [request-prompt request-exit]
                  (ensure-unrepl)
                  (loop []
                      (let [n (.read *in*)]
                        (if (neg? n)
                          request-exit
                          (let [c (char n)]
                            (cond
                              (or (Character/isWhitespace c) (= \, c)) (recur)
                              (= CTRL-D c) request-exit
                              (= CTRL-P c) (do (var-set command-mode true) (recur))
                              :else (do 
                                      (.unread *in* n)
                                      (m/repl-read request-prompt request-exit))))))))
          :eval (fn [form]
                  (let [id (var-set eval-id (inc @eval-id))]
                    (binding [*err* (tagging-writer :err id write)
                              *out* (tagging-writer :out id write)]
                      (if @command-mode
                        (let [command (get commands (first form))]
                          (throw (ex-info "Command" {::command (apply command (rest form))})))
                        (interruptible-eval form)))))
          :print (fn [x]
                   (ensure-unrepl)
                   (write [:eval x @eval-id]))
          :caught (fn [e]
                    (ensure-unrepl)
                    (cond
                      (identical? e interruption)
                      (write [:interrupted nil @eval-id])
                      (::command (ex-data e))
                      (write [:command (::command (ex-data e)) @eval-id])
                      :else (write [:exception e @eval-id]))))))))