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

(defn weak-store [make-action not-found]
  (let [ids-to-weakrefs (atom {})
        weakrefs-to-ids (atom {})
        refq (java.lang.ref.ReferenceQueue.)
        NULL (Object.)]
    (.start (Thread. (fn []
                       (let [wref (.remove refq)]
                         (let [id (@weakrefs-to-ids wref)]
                           (swap! weakrefs-to-ids dissoc wref)
                           (swap! ids-to-weakrefs dissoc id)))
                           (recur))))
    {:put (fn [x]
            (let [x (if (nil? x) NULL x)
                  id (keyword (gensym))
                  wref (java.lang.ref.WeakReference. x refq)]
              (swap! weakrefs-to-ids assoc wref id)
              (swap! ids-to-weakrefs assoc id wref)
              {:get (make-action id)}))
     :get (fn [id]
            (if-some [x (some-> @ids-to-weakrefs ^java.lang.ref.WeakReference (get id) .get)]
              (if (= NULL x) nil x)
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

(defonce ^:private sessions (atom {}))

(def ^:private unreachable (tagged-literal 'unrepl/... nil))
(defonce ^:private elision-store (weak-store #(list `fetch %) unreachable))
(defn fetch [id] 
  (let [x ((:get elision-store) id)]
    (cond
      (= unreachable x) x
      (instance? unrepl.print.ElidedKVs x) x
      :else (seq x))))

(defonce ^:private attachment-store (weak-store #(list `download %) (constantly nil)))
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
          edn-out (tagging-writer :out write-here)
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
                               :prompt-vars #{#'*ns* #'*warn-on-reflection*}})
          current-eval-thread+promise (atom nil)
          ensure-unrepl (fn []
                          (when-not @unrepl
                            (var-set unrepl true)
                            (flush)
                            (set! *out* edn-out)
                            (binding [*print-length* Long/MAX_VALUE
                                      *print-level* Long/MAX_VALUE]
                              (write [:unrepl/hello {:session session-id
                                                     :actions {:exit `(exit! ~session-id)
                                                               :log-eval
                                                               `(some-> ~session-id session :log-eval)
                                                               :log-all
                                                               `(some-> ~session-id session :log-all)
                                                               :set-source
                                                               `(unrepl/do
                                                                  (set-file-line-col ~session-id
                                                                   ~(tagged-literal 'unrepl/param :unrepl/sourcename)
                                                                   ~(tagged-literal 'unrepl/param :unrepl/line)
                                                                   ~(tagged-literal 'unrepl/param :unrepl/column)))}}]))))
          
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
                (swap! session-state assoc :current-eval {}))))]
      (swap! sessions assoc session-id session-state)
      (binding [*out* raw-out
                *err* (tagging-writer :err write)
                *in* in
                *file* "unrepl-session"
                *source-path* "unrepl-session"
                p/*elide* (:put elision-store)
                p/*attach* (:put attachment-store)
                write write-here]
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
                                               *out* (tagging-writer :out id write)]
                                       (eval (second r)))
                                     request-prompt)
                                   r))))
          :eval (fn [form]
                  (let [id @eval-id]
                    (binding [*err* (tagging-writer :err id write)
                              *out* (tagging-writer :out id write)]
                      (interruptible-eval form))))
          :print (fn [x]
                   (ensure-unrepl)
                   (write [:eval x @eval-id]))
          :caught (fn [e]
                    (ensure-unrepl)
                    (let [{:keys [::ex ::phase]
                           :or {ex e phase :repl}} (ex-data e)]
                      (write [:exception {:ex e :phase phase} @eval-id]))))
        (write [:bye {:reason :disconnection
                      :outs :muted
                      :actions {:reattach-outs `(reattach-outs! ~session-id)}}])))))
