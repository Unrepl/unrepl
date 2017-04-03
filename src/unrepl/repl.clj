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

(defn weak-store [make-action not-found]
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
                  id (keyword (gensym))
                  wref (java.lang.ref.WeakReference. xs refq)]
              (swap! weakrefs-to-ids assoc wref id)
              (swap! ids-to-weakrefs assoc id wref)
              {:get (make-action id)}))
     :get (fn [id]
            (if-some [xs (some-> @ids-to-weakrefs ^java.lang.ref.WeakReference (get id) .get)]
              (seq xs)
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

(defonce ^:private elision-store (weak-store #(list `fetch %) (tagged-literal 'unrepl/... nil)))
(defn fetch [id] ((:get elision-store) id))

(defonce ^:private attachment-store (weak-store #(list `download %) (constantly nil)))
(defn download [id] ((:get attachment-store) id))

(defn interrupt! [session eval]
  (let [{:keys [^Thread thread eval-id promise]}
        (some-> @sessions (get session) deref :current-eval)]
    (when (and (= eval eval-id)
            (deliver promise
              {:ex (doto (ex-info "Evaluation interrupted" {::phase :eval})
                     (.setStackTrace (.getStackTrace thread)))
               :bindings {}}))
      (.stop thread)
      true)))

(defn background! [session eval]
  (let [{:keys [eval-id promise future]}
        (some-> @sessions (get session) deref :current-eval)]
    (boolean
      (and
        (= eval eval-id)
        (deliver promise
          {:eval future
           :bindings {}})))))

(defn start []
  ; TODO: tighten by removing the dep on m/repl
  (with-local-vars [in-eval false
                    unrepl false
                    eval-id 0
                    prompt-vars #{#'*ns* #'*warn-on-reflection*}
                    current-eval-future nil]
    (let [session-id (keyword (gensym "session"))
          session-state (atom {:current-eval {}})
          current-eval-thread+promise (atom nil)
          raw-out *out*
          write (atomic-write raw-out)
          edn-out (tagging-writer :out write)
          ensure-unrepl (fn []
                          (when-not @unrepl
                            (var-set unrepl true)
                            (flush)
                            (set! *out* edn-out)
                            (binding [*print-length* Long/MAX_VALUE
                                      *print-level* Long/MAX_VALUE]
                              (write [:unrepl/hello {:session session-id
                                                     :actions {} #_{:exit (tagged-literal 'unrepl/raw CTRL-D)
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
                *in* (-> *in* (pre-reader ensure-raw-repl) clojure.lang.LineNumberingPushbackReader.)
                *file* "unrepl-session"
                *source-path* "unrepl-session"
                p/*elide* (:put elision-store)
                p/*attach* (:put attachment-store)]
        (m/repl
          :prompt (fn []
                    (ensure-unrepl)
                    (write [:prompt (into {}
                                      (map (fn [v]
                                             (let [m (meta v)]
                                               [(symbol (name (ns-name (:ns m))) (name (:name m))) @v])))
                                      (:prompt-vars @session-state))]))
          :read (fn [request-prompt request-exit]
                  (blame :read (m/repl-read request-prompt request-exit)))
          :eval (fn [form]
                  (let [id (var-set eval-id (inc @eval-id))]
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
                      (write [:exception {:ex e :phase phase} @eval-id]))))))))

