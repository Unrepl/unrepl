(ns unrepl.nrepl-bridge
  (:require [clojure.tools.nrepl :as nrepl]
    [clojure.tools.nrepl.transport :as t])
  (:use [clojure.tools.nrepl.misc :only (uuid)]))

(defn writer [transport session]
  (let [write (fn [s] 
                (t/send transport {:op :stdin :stdin s :id (uuid) :session session}))]
    (proxy [java.io.Writer] []
      (close [] (t/send transport {:op :close :id (uuid) :session session}))
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
                     off (+ off len)))))))))

(defn reader [transport session]
  (let [vr (volatile! (java.io.StringReader. ""))
        wait-ready (fn []
                     (when-some [msg (t/recv transport)]
                       (if-some [s (or (:out msg) (:err msg))]
                         (vreset! vr (java.io.StringReader. s))
                         (if (some #{"session-closed"} (:status msg))
                           (do
                             (.close transport)
                             (vreset! vr nil))
                           (recur)))))]
    (proxy [java.io.Reader] []
      (close [] (t/send transport {:op :close :id (uuid) :session session}))
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
        session (-> transport (t/send {:op :clone :id (uuid)}) t/recv :new-session)]
    (t/send transport {:op :eval :code "(clojure.main/repl)" :id (uuid) :session session})
    {:input (writer transport session)
     :output (reader transport session)}))