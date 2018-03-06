(ns unrepl.repl
  (:require [clojure.main :as m]
            [unrepl.core :as unrepl]
            [unrepl.print :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn classloader
  "Creates a classloader that obey standard delegating policy.
   Takes two arguments: a parent classloader and a function which
   takes a keyword (:resource or :class) and a string (a resource or a class name) and returns an array of bytes
   or nil."
  [parent f]
  (proxy [clojure.lang.DynamicClassLoader] [parent]
    (findResource [name]
      (when-some  [bytes (f :resource name)]
        (let [file (doto (java.io.File/createTempFile "unrepl-sideload-" (str "-" (re-find #"[^/]*$" name)))
                     .deleteOnExit)]
          (io/copy bytes file)
          (-> file .toURI .toURL))))
    (findClass [name]
      (if-some  [bytes (f :class name)]
        (.defineClass ^clojure.lang.DynamicClassLoader this name bytes nil)
        (throw (ClassNotFoundException. name))))))

(defn ^java.io.Writer tagging-writer
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

(defn blame-ex [phase ex]
  (if (::phase (ex-data ex))
    ex
    (ex-info (str "Exception during " (name phase) " phase.")
             {::ex ex ::phase phase} ex)))

(defmacro blame [phase & body]
  `(try ~@body
        (catch Throwable t#
          (throw (blame-ex ~phase t#)))))

(defn atomic-write [^java.io.Writer w]
  (fn [x]
    (let [s (blame :print (p/edn-str x))] ; was pr-str, must occur outside of the locking form to avoid deadlocks
      (locking w
        (.write w s)
        (.write w "\n")
        (.flush w)))))

(defn unrepl-reader [^java.io.Reader r]
  (let [offset (atom 0)
        offset! #(swap! offset + %)]
    (proxy [clojure.lang.LineNumberingPushbackReader clojure.lang.ILookup] [r]
      (valAt
        ([k] (get this k nil))
        ([k not-found] (case k :offset @offset not-found)))
      (read
        ([]
         (let [c (proxy-super read)]
           (when-not (neg? c) (offset! 1))
           c))
        ([cbuf]
         (let [n (proxy-super read cbuf)]
           (when (pos? n) (offset! n))
           n))
        ([cbuf off len]
         (let [n (proxy-super read cbuf off len)]
           (when (pos? n) (offset! n))
           n)))
      (unread
        ([c-or-cbuf]
         (if (integer? c-or-cbuf)
           (when-not (neg? c-or-cbuf) (offset! -1))
           (offset! (- (alength c-or-cbuf))))
         (proxy-super unread c-or-cbuf))
        ([cbuf off len]
         (offset! (- len))
         (proxy-super unread cbuf off len)))
      (skip [n]
        (let [n (proxy-super skip n)]
          (offset! n)
          n))
      (readLine []
        (when-some [s (proxy-super readLine)]
          (offset! (count s))
          s)))))

(defn soft-store [make-action]
  (let [ids-to-session+refs (atom {})
        refs-to-ids (atom {})
        refq (java.lang.ref.ReferenceQueue.)
        NULL (Object.)]
    (.start (Thread. (fn []
                       (let [ref (.remove refq)]
                         (let [id (@refs-to-ids ref)]
                           (swap! refs-to-ids dissoc ref)
                           (swap! ids-to-session+refs dissoc id)))
                       (recur))))
    {:put (fn [session-id x]
            (let [x (if (nil? x) NULL x)
                  id (keyword (gensym))
                  ref (java.lang.ref.SoftReference. x refq)]
              (swap! refs-to-ids assoc ref id)
              (swap! ids-to-session+refs assoc id [session-id ref])
              {:get (make-action id)}))
     :get (fn [id]
            (when-some [[session-id  ^java.lang.ref.Reference r] (@ids-to-session+refs id)]
              (let [x (.get r)]
                [session-id (if (= NULL x) nil x)])))}))

(defonce ^:private sessions (atom {}))

(defn session [id]
  (some-> @sessions (get id) deref))

(defonce ^:private elision-store (soft-store #(list `fetch %)))
(defn fetch [id]
  (if-some [[session-id x] ((:get elision-store) id)]
    (unrepl.print.WithBindings.
      (select-keys (some-> session-id session :bindings) [#'*print-length* #'*print-level* #'unrepl/*string-length* #'p/*elide*])
      (cond
        (instance? unrepl.print.ElidedKVs x) x
        (string? x) x
        (instance? unrepl.print.MimeContent x) x
        :else (seq x)))
    p/unreachable))

(defn interrupt! [session-id eval]
  (let [{:keys [^Thread thread eval-id promise]}
        (some-> session-id session :current-eval)]
    (when (and (= eval eval-id)
               (deliver promise
                        {:ex (doto (ex-info "Evaluation interrupted" {::phase :eval})
                               (.setStackTrace (.getStackTrace thread)))
                         :bindings {}}))
      (.stop thread)
      true)))

(defn background! [session-id eval]
  (let [{:keys [eval-id promise future]}
        (some-> session-id session :current-eval)]
    (boolean
     (and
      (= eval eval-id)
      (deliver promise
               {:eval future
                :bindings {}})))))

(defn attach-sideloader! [session-id]
  (prn '[:unrepl.jvm.side-loader/hello])
  (some-> session-id session :side-loader
          (reset!
           (let [out *out*
                 in *in*]
             (fn self [k name]
               (binding [*out* out]
                 (locking self
                   (prn [k name])
                   (some-> (edn/read {:eof nil} in) p/base64-decode)))))))
  (let [o (Object.)] (locking o (.wait o))))

(defn set-file-line-col [session-id file line col]
  (when-some [^java.lang.reflect.Field field
              (->> clojure.lang.LineNumberingPushbackReader
                   .getDeclaredFields
                   (some #(when (= "_columnNumber" (.getName ^java.lang.reflect.Field %)) %)))]
    (doto field (.setAccessible true)) ; sigh
    (when-some [in (some-> session-id session :in)]
      (set! *file* file)
      (set! *source-path* file)
      (.setLineNumber in line)
      (.set field in (int col)))))

(def schedule-flushes!
  (let [thread-pool (java.util.concurrent.Executors/newScheduledThreadPool 1)
        max-latency-ms 20] ; 50 flushes per second
    (fn [w]
      (let [wr (java.lang.ref.WeakReference. w)
            vfut (volatile! nil)]
        (vreset! vfut
          (.scheduleAtFixedRate
            thread-pool
            (fn []
              (if-some [^java.io.Writer w (.get wr)]
                (.flush w)
                (.cancel ^java.util.concurrent.Future @vfut)))
            max-latency-ms max-latency-ms java.util.concurrent.TimeUnit/MILLISECONDS))))))

(defn scheduled-writer [& args]
  (-> (apply tagging-writer args)
    java.io.BufferedWriter.
    (doto schedule-flushes!)))

(defmacro ^:private flushing [bindings & body]
  `(binding ~bindings
     (try ~@body
          (finally ~@(for [v (take-nth 2 bindings)]
                       `(.flush ~(vary-meta v assoc :tag 'java.io.Writer)))))))

(def ^:dynamic eval-id)

(defn unrepl-read [request-prompt request-exit]
  (blame :read (let [id (set! eval-id (inc eval-id))
                     line+col [(.getLineNumber *in*) (.getColumnNumber *in*)]
                     offset (:offset *in*)
                     r (m/repl-read request-prompt request-exit)
                     line+col' [(.getLineNumber *in*) (.getColumnNumber *in*)]
                     offset' (:offset *in*)
                     len (- offset' offset)]
                 (unrepl/write [:read {:from line+col :to line+col'
                                     :offset offset
                                     :len (- offset' offset)}
                              id])
                 (if (and (seq?  r) (= (first r) 'unrepl/do))
                   (do
                     (flushing [*err* (tagging-writer :err id unrepl/non-eliding-write)
                                *out* (scheduled-writer :out id unrepl/non-eliding-write)]
                       (eval (cons 'do (next r))))
                     request-prompt)
                   r))))

(defn start []
  (with-local-vars [prompt-vars #{#'*ns* #'*warn-on-reflection*}
                    current-eval-future nil]
    (let [session-id (keyword (gensym "session"))
          raw-out *out*
          in (unrepl-reader *in*)
          session-state (atom {:current-eval {}
                               :in in
                               :log-eval (fn [msg]
                                           (when (bound? eval-id)
                                             (unrepl/write [:log msg eval-id])))
                               :log-all (fn [msg]
                                          (unrepl/write [:log msg nil]))
                               :side-loader (atom nil)
                               :prompt-vars #{#'*ns* #'*warn-on-reflection*}})
          current-eval-thread+promise (atom nil)
          say-hello
          (fn []
            (unrepl/non-eliding-write
              [:unrepl/hello {:session session-id
                              :actions (into
                                         {:start-aux `(start-aux ~session-id)
                                          :log-eval
                                          `(some-> ~session-id session :log-eval)
                                          :log-all
                                          `(some-> ~session-id session :log-all)
                                          :print-limits
                                          `(let [bak# {:unrepl.print/string-length unrepl/*string-length*
                                                       :unrepl.print/coll-length *print-length*
                                                       :unrepl.print/nesting-depth *print-level*}]
                                             (some->> ~(tagged-literal 'unrepl/param :unrepl.print/string-length) (set! unrepl/*string-length*))
                                             (some->> ~(tagged-literal 'unrepl/param :unrepl.print/coll-length) (set! *print-length*))
                                             (some->> ~(tagged-literal 'unrepl/param :unrepl.print/nesting-depth) (set! *print-level*))
                                             bak#)
                                          :set-source
                                          `(~'unrepl/do
                                             (set-file-line-col ~session-id
                                                                ~(tagged-literal 'unrepl/param :unrepl/sourcename)
                                                                ~(tagged-literal 'unrepl/param :unrepl/line)
                                                                ~(tagged-literal 'unrepl/param :unrepl/column)))
                                          :unrepl.jvm/start-side-loader
                                          `(attach-sideloader! ~session-id)}
                                #_ext-session-actions{})}]))

          interruptible-eval
          (fn [form]
            (try
              (let [original-bindings (get-thread-bindings)
                    p (promise)
                    f
                    (future
                      (swap! session-state update :current-eval
                             assoc :thread (Thread/currentThread))
                      (with-bindings original-bindings
                        (try
                          (unrepl/non-eliding-write
                            [:started-eval
                             {:actions
                              {:interrupt (list `interrupt! session-id eval-id)
                               :background (list `background! session-id eval-id)}}
                             eval-id])
                          (let [v (blame :eval (eval form))]
                            (deliver p {:eval v :bindings (get-thread-bindings)})
                            v)
                          (catch Throwable t
                            (deliver p {:ex t :bindings (get-thread-bindings)})
                            (throw t)))))]
                (swap! session-state update :current-eval
                       into {:eval-id eval-id :promise p :future f})
                (let [{:keys [ex eval bindings]} @p]
                  (swap! session-state assoc :bindings bindings)
                  (doseq [[var val] bindings
                          :when (not (identical? val (original-bindings var)))]
                    (var-set var val))
                  (if ex
                    (throw ex)
                    eval)))
              (finally
                (swap! session-state assoc :current-eval {}))))
          cl (.getContextClassLoader (Thread/currentThread))
          slcl (classloader cl
                            (fn [k x]
                              (when-some [f (some-> session-state deref :side-loader deref)]
                                (f k x))))]
      (swap! session-state assoc :class-loader slcl)
      (swap! sessions assoc session-id session-state)
      (binding [*out* (scheduled-writer :out unrepl/non-eliding-write)
                *err* (tagging-writer :err unrepl/non-eliding-write)
                *in* in
                *file* "unrepl-session"
                *source-path* "unrepl-session"
                p/*elide* (partial (:put elision-store) session-id)
                unrepl/*string-length* unrepl/*string-length*
                unrepl/write (atomic-write raw-out)
                unrepl/read unrepl-read
                eval-id 0]
        (.setContextClassLoader (Thread/currentThread) slcl)
        (with-bindings {clojure.lang.Compiler/LOADER slcl}
          (try
            (m/repl
             :init #(do
                      (swap! session-state assoc :bindings (get-thread-bindings))
                      (say-hello))
             :prompt (fn []
                       (unrepl/non-eliding-write [:prompt (into {:file *file*
                                                                 :line (.getLineNumber *in*)
                                                                 :column (.getColumnNumber *in*)
                                                                 :offset (:offset *in*)}
                                                            (map (fn [v]
                                                                   (let [m (meta v)]
                                                                     [(symbol (name (ns-name (:ns m))) (name (:name m))) @v])))
                                                            (:prompt-vars @session-state))]))
             :read unrepl/read
             :eval (fn [form]
                     (flushing [*err* (tagging-writer :err eval-id unrepl/non-eliding-write)
                                *out* (scheduled-writer :out eval-id unrepl/non-eliding-write)]
                       (interruptible-eval form)))
             :print (fn [x]
                      (unrepl/write [:eval x eval-id]))
             :caught (fn [e]
                       (let [{:keys [::ex ::phase]
                              :or {ex e phase :repl}} (ex-data e)]
                         (unrepl/write [:exception {:ex ex :phase phase} eval-id]))))
            (finally
              (.setContextClassLoader (Thread/currentThread) cl))))))))

(defn start-aux [session-id]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (try
      (some->> session-id session :class-loader (.setContextClassLoader (Thread/currentThread)))
      (start)
      (finally
        (.setContextClassLoader (Thread/currentThread) cl)))))

(defmacro ensure-ns [[fully-qualified-var-name & args :as expr]]
  `(do
     (require '~(symbol (namespace fully-qualified-var-name)))
     ~expr))