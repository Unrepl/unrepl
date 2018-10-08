(ns unrepl.repl
  (:require [clojure.main :as m]
            [unrepl.core :as unrepl]
            [unrepl.printer :as p]
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
    (if (and (vector? x) (= (count x) 3))
      (let [[tag payload id] x
            s (blame :print (str "[" (p/edn-str tag)
                              " " (p/edn-str payload)
                              " " (p/edn-str id) "]"))] ; was pr-str, must occur outside of the locking form to avoid deadlocks
        (locking w
          (.write w s)
          (.write w "\n")
          (.flush w)))
      (let [s (blame :print (p/edn-str x))] ; was pr-str, must occur outside of the locking form to avoid deadlocks
        (locking w
          (.write w s)
          (.write w "\n")
          (.flush w))))))

(definterface ILocatedReader
  (setCoords [coords-map]))

(defn unrepl-reader [^java.io.Reader r]
  (let [offset (atom 0)
        last-reset (volatile! {:col-off 0 :line 0 :file (str (gensym "unrepl-reader-"))})
        offset! #(swap! offset + %)]
    (proxy [clojure.lang.LineNumberingPushbackReader clojure.lang.ILookup ILocatedReader] [r]
      (getColumnNumber []
        (let [{:keys [line col-off]} @last-reset
              off (if (= (.getLineNumber this) line) col-off 0)]
          (+ off (proxy-super getColumnNumber))))
      (setCoords [{:keys [line col name]}]
        (locking this
          (when line (.setLineNumber this line))
          (let [line (.getLineNumber this)
                col-off (if col (- col (.getColumnNumber this)) 0)
                name (or name (:file @last-reset))]
            (vreset! last-reset {:line line :col-off col-off :file name})))
        (:coords this))
      (valAt
        ([k] (get this k nil))
        ([k not-found] (case k
                         :offset @offset
                         :coords {:offset @offset
                                  :line (.getLineNumber this)
                                  :col (.getColumnNumber this)
                                  :file (:file @last-reset)}
                         not-found)))
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

(defn ensure-unrepl-reader 
  ([rdr]
    (if (instance? ILocatedReader rdr)
      rdr
      (unrepl-reader rdr)))
  ([rdr name]
    (if (instance? ILocatedReader rdr)
      rdr
      (doto (unrepl-reader rdr)
        (.setCoords {:file name})))))

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
    (unrepl.printer.WithBindings.
      (select-keys (some-> session-id session :bindings) [#'*print-length* #'*print-level* #'unrepl/*string-length* #'p/*elide*])
      (cond
        (instance? unrepl.printer.ElidedKVs x) x
        (string? x) x
        (instance? unrepl.printer.MimeContent x) x
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

(defn enqueue [session-id f]
  (some-> session-id session ^java.util.concurrent.BlockingQueue (:actions-queue) (.put f)))

(defn set-file-line-col [session-id file line col]
  (enqueue session-id #(when-some [in (some-> session-id session :in)]
                         (set! *file* file)
                         (set! *source-path* file)
                         (.setCoords ^ILocatedReader in {:line line :col col :file file}))))

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

(def ^:dynamic interrupted? (constantly false))

(defn seek-readable
  "Skips whitespace and comments on stream s. Returns true when a form may be read,
  false otherwise.
  Note that returning true does not guarantee that the next read will yield something.
  (It may be EOF, or a discard #_ or a non-matching conditional...)"
  [s]
  (loop [comment false]
    (let [c (.read s)]
      (cond
        (interrupted?) (do (.unread s c) false)
        (= c (int \newline)) false
        comment (recur comment)
        (= c -1) true
        (= c (int \;)) (recur true)
        (or (Character/isWhitespace (char c)) (= c (int \,))) (recur comment)
        :else (do (.unread s c) true)))))

(defn unrepl-read [request-prompt request-exit]
  (blame :read 
    (if (seek-readable *in*)
      (let [coords (:coords *in*)]
        (try 
          (read {:read-cond :allow :eof request-exit} *in*)
          (finally
            (let [coords' (:coords *in*)]
              (unrepl/write [:read {:file (:file coords)
                                    :from [(:line coords) (:col coords)] :to [(:line coords') (:col coords')]
                                    :offset (:offset coords)
                                    :len (- (:offset coords') (:offset coords))}
                             eval-id])))))
      request-prompt)))

(defn start [ext-session-actions]
  (with-local-vars [prompt-vars #{#'*ns* #'*warn-on-reflection*}
                    current-eval-future nil]
    (let [ext-session-actions
          (into {}
            (map (fn [[k v]]
                   [k (if (and (seq? v) (symbol? (first v)) (namespace (first v)))
                        (list `ensure-ns v)
                        v)]))
            ext-session-actions)
          session-id (keyword (gensym "session"))
          raw-out *out*
          in (ensure-unrepl-reader *in* (str "unrepl-" (name session-id)))
          actions-queue (java.util.concurrent.LinkedBlockingQueue.)
          session-state (atom {:current-eval {}
                               :in in
                               :log-eval (fn [msg]
                                           (when (bound? eval-id)
                                             (unrepl/write [:log msg eval-id])))
                               :log-all (fn [msg]
                                          (unrepl/write [:log msg nil]))
                               :side-loader (atom nil)
                               :prompt-vars #{#'*ns* #'*warn-on-reflection*}
                               :actions-queue actions-queue})
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
                                          `(set-file-line-col ~session-id
                                             ~(tagged-literal 'unrepl/param :unrepl/sourcename)
                                             ~(tagged-literal 'unrepl/param :unrepl/line)
                                             ~(tagged-literal 'unrepl/param :unrepl/column))
                                          :unrepl.jvm/start-side-loader
                                          `(attach-sideloader! ~session-id)}
                                         ext-session-actions)}]))

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
                *file* (-> in :coords :file)
                *source-path* *file*
                p/*elide* (partial (:put elision-store) session-id)
                unrepl/*string-length* unrepl/*string-length*
                unrepl/write (atomic-write raw-out)
                unrepl/read unrepl-read
                eval-id 0
                interrupted? #(.peek actions-queue)]
        (.setContextClassLoader (Thread/currentThread) slcl)
        (with-bindings {clojure.lang.Compiler/LOADER slcl}
          (try
            (m/repl
             :init #(do
                      (swap! session-state assoc :bindings (get-thread-bindings))
                      (say-hello))
             :need-prompt (constantly true)
             :prompt (fn []
                       (when-some [f (.poll actions-queue)] (f))
                       (unrepl/non-eliding-write [:prompt (into {:file *file*
                                                                 :line (.getLineNumber *in*)
                                                                 :column (.getColumnNumber *in*)
                                                                 :offset (:offset *in*)}
                                                            (map (fn [v]
                                                                   (let [m (meta v)]
                                                                     [(symbol (name (ns-name (:ns m))) (name (:name m))) @v])))
                                                            (:prompt-vars @session-state))
                                                  (set! eval-id (inc eval-id))]))
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