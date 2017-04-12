(ns unrepl.nrepl-bridge
  (:require [clojure.tools.nrepl :as nrepl]
    [clojure.tools.nrepl.transport :as t])
  (:use [clojure.tools.nrepl.misc :only (uuid)]))

(defn writer [transport close write]
  (proxy [java.io.Writer] []
    (close [] (close))
    (flush [])
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

(defn reader [transport close flush]
  (let [vr (volatile! (java.io.StringReader. ""))
        wait-ready (fn []
                     (when-some [msg (t/recv transport Long/MAX_VALUE)]
                       (if-some [s (or (:out msg) (:err msg))]
                         (vreset! vr (java.io.StringReader. s))
                         (if (some #{"session-closed"} (:status msg))
                           (do
                             (.close transport)
                             (vreset! vr nil))
                           (do
                             (when (some #{"need-input"} (:status msg))
                               (flush)
                               (recur)))))))]
    (proxy [java.io.Reader] []
      (close [] (close))
      (read
        ([] (if-some [^java.io.Reader r @vr]
              (let [c (.read r)]
                (if (neg? c)
                  (do (wait-ready) (recur this))
                  c))
              -1))
        ([cbuf]
          (if-some [^java.io.Reader r @vr]
            (let [c (if (instance? java.nio.CharBuffer cbuf)
                      (.read r ^java.nio.CharBuffer cbuf)
                      (.read r ^chars cbuf))]
              (if (neg? c)
                (do (wait-ready) (recur this cbuf))
                c))
              -1))
        ([cbuf off len]
          (if-some [^java.io.Reader r @vr]
            (let [c (.read r cbuf off len)]
              (if (neg? c)
                (do (wait-ready) (recur this cbuf off len))
                c))
              -1))))))

(defn repl
  "Starts a plain repl over nrepl. Returns a map with two keys :output and :input.
   The value under :input is a writer and the one under :output is a reader connected to the repl.
   Takes the same arguments as nrepl/connect."
  [& args]
  (let [transport (apply nrepl/connect args)
        session (-> transport (t/send {:op :clone :id (uuid)}) (t/recv Long/MAX_VALUE) :new-session)
        close #(t/send transport {:op :close :id (uuid) :session session})
        q (java.util.concurrent.LinkedBlockingQueue.)
        write (fn [x] (.put q x))
        flush (fn []
                (let [s (loop [sb (StringBuilder. (.take q))]
                          (if-some [s (.poll q)]
                            (recur (.append sb s))
                            (.toString sb)))]
                  (t/send transport
                    {:op :stdin :stdin s 
                     :id (uuid) :session session})))]
    (t/send transport {:op :eval :code "(clojure.main/repl)" :id (uuid) :session session})
    {:input (writer transport close write)
     :output (reader transport close flush)}))

(comment
  ; the following example upgrades the running repl to mirror the replized nrepl :-)
  (let [{:keys [output input]} (repl :port 62005)
        cbuf (char-array 1024)
        cbuf' (char-array 1024)]
    (future
      (loop []
        (let [n (.read output cbuf)]
          (when-not (neg? n)
            (doto *out*
              (.write cbuf 0 n)
              (.flush))
            (recur)))))
    (loop []
      (let [n (.read *in* cbuf')]
        (when-not (neg? n)
          (.write input cbuf' 0 n)
          (recur))))))