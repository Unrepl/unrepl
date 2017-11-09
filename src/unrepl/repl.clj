(ns unrepl.repl
  (:require [clojure.main :as m]
    [unrepl.print :as p]
    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(defn classloader
  "Creates a classloader that obey standard delegating policy.
   Takes two arguments: a parent classloader and a function which
   takes a keyword (:resource or :class) and a string (a resource or a class name) and returns an array of bytes
   or nil."
  [parent f]
  (let [define-class (doto (.getDeclaredMethod ClassLoader "defineClass" (into-array [String (Class/forName "[B") Integer/TYPE Integer/TYPE]))
                       (.setAccessible true))]
    (proxy [ClassLoader] [parent]
      (findResource [name]
        (when-some  [bytes (f :resource name)]
          (let [file (doto (java.io.File/createTempFile "unrepl-sideload-" (str "-" (re-find #"[^/]*$" name)))
                       .deleteOnExit)]
            (io/copy bytes file)
            (-> file .toURI .toURL))))
      (findClass [name]
        (if-some  [bytes (f :class name)]
          (.invoke define-class this (to-array name bytes 0 (count bytes)))
          (throw (ClassNotFoundException. name)))))))

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

(defn fuse-write [awrite]
  (fn [x]
    (when-some [w @awrite]
      (try
        (w x)
        (catch Throwable t
          (reset! awrite nil))))))

(def ^:dynamic write)

(defn unrepl-reader [^java.io.Reader r before-read]
  (let [offset (atom 0)
        offset! #(swap! offset + %)]
    (proxy [clojure.lang.LineNumberingPushbackReader clojure.lang.ILookup] [r]
      (valAt
        ([k] (get this k nil))
        ([k not-found] (case k :offset @offset not-found)))
      (read
        ([]
          (before-read)
          (let [c (proxy-super read)]
            (when-not (neg? c) (offset! 1))
            c))
        ([cbuf]
          (before-read)
          (let [n (proxy-super read cbuf)]
            (when (pos? n) (offset! n))
            n))
        ([cbuf off len]
          (before-read)
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

(defn- close-socket! [x]
  ; hacky way because the socket is not exposed by clojure.core.server
  (loop [x x]
    (if (= "java.net.SocketInputStream" (.getName (class x)))
      (do (.close x) true)
      (when-some [^java.lang.reflect.Field field 
                  (->> x class (iterate #(.getSuperclass %)) (take-while identity)
                    (mapcat #(.getDeclaredFields %))
                    (some #(when (#{"in" "sd"} (.getName ^java.lang.reflect.Field %)) %)))]
        (recur (.get (doto field (.setAccessible true)) x))))))

(defn soft-store [make-action not-found]
  (let [ids-to-refs (atom {})
        refs-to-ids (atom {})
        refq (java.lang.ref.ReferenceQueue.)
        NULL (Object.)]
    (.start (Thread. (fn []
                       (let [ref (.remove refq)]
                         (let [id (@refs-to-ids ref)]
                           (swap! refs-to-ids dissoc ref)
                           (swap! ids-to-refs dissoc id)))
                           (recur))))
    {:put (fn [x]
            (let [x (if (nil? x) NULL x)
                  id (keyword (gensym))
                  ref (java.lang.ref.SoftReference. x refq)]
              (swap! refs-to-ids assoc ref id)
              (swap! ids-to-refs assoc id ref)
              {:get (make-action id)}))
     :get (fn [id]
            (if-some [x (some-> @ids-to-refs ^java.lang.ref.Reference (get id) .get)]
              (if (= NULL x) nil x)
              not-found))}))

(defn- base64-encode [^java.io.InputStream in]
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

(defn- base64-decode [^String s]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        in (java.io.StringReader. s)
        bos (java.io.ByteArrayOutputStream.)]
    (loop [bits 0 buf 0]
      (let [got (.read in)]
        (when-not (or (neg? got) (= 61 #_\= got))
          (let [buf (bit-or (.indexOf table got) (bit-shift-left buf 6))
                bits (+ bits 6)]
            (if (<= 8 bits)
              (let [bits (- bits 8)]
                (.write bos (bit-shift-right buf bits))
                (recur bits (bit-and 63 buf)))
              (recur bits buf))))))
    (.toByteArray bos)))

(defonce ^:private sessions (atom {}))

(def ^:private unreachable (tagged-literal 'unrepl/... nil))
(defonce ^:private elision-store (soft-store #(list `fetch %) unreachable))
(defn fetch [id] 
  (let [x ((:get elision-store) id)]
    (cond
      (= unreachable x) x
      (instance? unrepl.print.ElidedKVs x) x
      (string? x) x
      :else (seq x))))

(defonce ^:private attachment-store (soft-store #(list `download %) (constantly nil)))
(defn download [id] ((:get attachment-store) id))

(defn session [id]
  (some-> @sessions (get id) deref))

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

(defn exit! [session-id] ; too violent
  (some-> session-id session :in close-socket!))

(defn reattach-outs! [session-id]
  (some-> session-id session :write-atom 
    (reset!
      (if (bound? #'write)
        write
        (let [out *out*]
          (fn [x]
            (binding [*out* out
                      *print-readably* true]
              (prn x))))))))

(defn attach-sideloader! [session-id]
  (some-> session-id session :side-loader 
    (reset!
      (let [out *out*
            in *in*]
        (fn self [k name]
          (binding [*out* out]
            (locking self
              (prn [k name])
              (some-> (edn/read {:eof nil} in) base64-decode)))))))
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

(defn- writers-flushing-repo [max-latency-ms]
  (let [writers (java.util.WeakHashMap.)
        flush-them-all #(locking writers
                          (doseq [^java.io.Writer w (.keySet writers)]
                            (.flush w)))]
    (.scheduleAtFixedRate
      (java.util.concurrent.Executors/newScheduledThreadPool 1)
      flush-them-all
      max-latency-ms max-latency-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    (fn [w]
      (locking writers (.put writers w nil)))))

(defn start []
  (with-local-vars [in-eval false
                    unrepl false
                    eval-id 0
                    prompt-vars #{#'*ns* #'*warn-on-reflection*}
                    current-eval-future nil]
    (let [session-id (keyword (gensym "session"))
          raw-out *out*
          aw (atom (atomic-write raw-out))
          write-here (fuse-write aw)
          schedule-writer-flush! (writers-flushing-repo 50) ; 20 fps (flushes per second)
          scheduled-writer (fn [& args]
                             (-> (apply tagging-writer args)
                               java.io.BufferedWriter.
                               (doto schedule-writer-flush!)))
          edn-out (scheduled-writer :out write-here)
          ensure-raw-repl (fn []
                            (when (and @in-eval @unrepl) ; reading from eval!
                              (var-set unrepl false)
                              (write [:bye {:reason :upgrade :actions {}}])
                              (flush)
                              ; (reset! aw (blocking-write))
                              (set! *out* raw-out)))
          in (unrepl-reader *in* ensure-raw-repl)
          session-state (atom {:current-eval {}
                               :in in
                               :write-atom aw
                               :log-eval (fn [msg]
                                           (when (bound? eval-id)
                                             (write [:log msg @eval-id])))
                               :log-all (fn [msg]
                                          (write [:log msg nil]))
                               :side-loader (atom nil)
                               :prompt-vars #{#'*ns* #'*warn-on-reflection*}})
          current-eval-thread+promise (atom nil)
          ensure-unrepl (fn []
                          (when-not @unrepl
                            (var-set unrepl true)
                            (flush)
                            (set! *out* edn-out)
                            (binding [*print-length* Long/MAX_VALUE
                                      *print-level* Long/MAX_VALUE
                                      p/*string-length* Long/MAX_VALUE]
                              (write [:unrepl/hello {:session session-id
                                                     :actions {:exit `(exit! ~session-id)
                                                               :start-aux `(start-aux ~session-id)
                                                               :log-eval
                                                               `(some-> ~session-id session :log-eval)
                                                               :log-all
                                                               `(some-> ~session-id session :log-all)
                                                               :print-limits
                                                               `(let [bak# {:unrepl.print/string-length p/*string-length*
                                                                            :unrepl.print/coll-length *print-length*
                                                                            :unrepl.print/nesting-depth *print-level*}]
                                                                  (some->> ~(tagged-literal 'unrepl/param :unrepl.print/string-length) (set! p/*string-length*))
                                                                  (some->> ~(tagged-literal 'unrepl/param :unrepl.print/coll-length) (set! *print-length*))
                                                                  (some->> ~(tagged-literal 'unrepl/param :unrepl.print/nesting-depth) (set! *print-level*))
                                                                  bak#)
                                                               :set-source
                                                               `(unrepl/do
                                                                  (set-file-line-col ~session-id
                                                                   ~(tagged-literal 'unrepl/param :unrepl/sourcename)
                                                                   ~(tagged-literal 'unrepl/param :unrepl/line)
                                                                   ~(tagged-literal 'unrepl/param :unrepl/column)))
                                                               :unrepl.jvm/start-side-loader
                                                               `(attach-sideloader! ~session-id)}}]))))
          
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
                          (write [:started-eval
                                  {:actions 
                                   {:interrupt (list `interrupt! session-id @eval-id)
                                    :background (list `background! session-id @eval-id)}}
                                  @eval-id])
                          (let [v (with-bindings {in-eval true}
                                    (blame :eval (eval form)))]
                            (deliver p {:eval v :bindings (get-thread-bindings)})
                            v)
                          (catch Throwable t
                            (deliver p {:ex t :bindings (get-thread-bindings)})
                            (throw t)))))]
                (swap! session-state update :current-eval
                  into {:eval-id @eval-id :promise p :future f})
                (let [{:keys [ex eval bindings]} @p]
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
      (binding [*out* raw-out
                *err* (tagging-writer :err write)
                *in* in
                *file* "unrepl-session"
                *source-path* "unrepl-session"
                p/*elide* (:put elision-store)
                p/*attach* (:put attachment-store)
                p/*string-length* p/*string-length* 
                write write-here]
        (.setContextClassLoader (Thread/currentThread) slcl)
        (with-bindings {clojure.lang.Compiler/LOADER slcl}
          (try
            (m/repl
              :prompt (fn []
                        (ensure-unrepl)
                        (write [:prompt (into {:file *file*
                                               :line (.getLineNumber *in*)
                                               :column (.getColumnNumber *in*)
                                               :offset (:offset *in*)}
                                          (map (fn [v]
                                                 (let [m (meta v)]
                                                   [(symbol (name (ns-name (:ns m))) (name (:name m))) @v])))
                                          (:prompt-vars @session-state))]))
             :read (fn [request-prompt request-exit]
                     (blame :read (let [line+col [(.getLineNumber *in*) (.getColumnNumber *in*)]
                                        offset (:offset *in*)
                                        r (m/repl-read request-prompt request-exit)
                                        line+col' [(.getLineNumber *in*) (.getColumnNumber *in*)]
                                        offset' (:offset *in*)
                                        len (- offset' offset)
                                        id (when-not (#{request-prompt request-exit} r)
                                             (var-set eval-id (inc @eval-id)))]
                                    (write [:read {:from line+col :to line+col'
                                                   :offset offset
                                                   :len (- offset' offset)}
                                            id])
                                    (if (and (seq?  r) (= (first r) 'unrepl/do))
                                      (let [id @eval-id]
                                        (binding [*err* (tagging-writer :err id write)
                                                  *out* (scheduled-writer :out id write)]
                                          (eval (cons 'do (next r))))
                                        request-prompt)
                                      r))))
             :eval (fn [form]
                     (let [id @eval-id]
                       (binding [*err* (tagging-writer :err id write)
                                 *out* (scheduled-writer :out id write)]
                         (interruptible-eval form))))
             :print (fn [x]
                      (ensure-unrepl)
                      (write [:eval x @eval-id]))
             :caught (fn [e]
                       (ensure-unrepl)
                       (let [{:keys [::ex ::phase]
                              :or {ex e phase :repl}} (ex-data e)]
                         (write [:exception {:ex e :phase phase} @eval-id]))))
           (finally
             (.setContextClassLoader (Thread/currentThread) cl))))
        (write [:bye {:reason :disconnection
                      :outs :muted
                      :actions {:reattach-outs `(reattach-outs! ~session-id)}}])))))

(defn start-aux [session-id]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (try
        (some->> session-id session :class-loader (.setContextClassLoader (Thread/currentThread)))
        (start)
        (finally
          (.setContextClassLoader (Thread/currentThread) cl)))))
