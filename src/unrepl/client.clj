(ns unrepl.client
  "This namespace is meant for clients: it composes messages according to the description sent by the repl and the available data."
  (require [unrepl.print :as p]))

(defn- param? [x]
  (and (tagged-literal? x) (= 'unrepl/param (:tag x))))

(declare encoding)

(def ^:dynamic *encoding* {'unrepl/raw (fn [lookup {:keys [form]}]
                                         (letfn [(raw-printer [form]
                                                   (cond
                                                     (vector? form) (doseq [x form] (raw-printer x))
                                                     (char? form) (print form)
                                                     (string? form) (print form)
                                                     (param? form) (print (lookup form))
                                                     (encoding form) (let [f (encoding form)]
                                                                       (print (f lookup form)))
                                                     :else (throw (ex-info "Unsupported raw template." {:form form}))))]
                                           (with-out-str
                                             (raw-printer form))))
                           'unrepl/edn (fn [lookup {:keys [form]}]
                                         (binding 
                                           [p/*ednize-fns*
                                            (update p/*ednize-fns* clojure.lang.TaggedLiteral
                                              (fnil (fn [f]
                                                      (fn [lit]
                                                        (if (param? lit)
                                                          (p/ednize (lookup lit))
                                                          (f lit)))) identity))]
                                           (p/full-edn-str form)))})

(defn- encoding [x]
  (and (tagged-literal? x) (*encoding* (:tag x))))

(defn msg-str
  "Compose a message, the message description ias assumed to have been read
   with `tagged-literal` as the reader for 'unrepl/raw, 'unrepl/edn and 'unrepl/param.
   For example using `(clojure.edn/read {:default tagged-literal} *in*)`."
  ([msg] (msg-str msg {} {}))
  ([msg params] (msg-str msg params {}))
  ([msg params aliases]
    (let [alias-fn (if (fn? aliases) aliases #(get aliases % %))
          lookup (fn [{param :form}]
                   (when-not (and (keyword? param) (namespace param))
                     (throw (ex-info "Params must be qualified keywords." {:msg msg :param param})))
                   (let [k (alias-fn param)]
                     (if-some [[_ v] (find params k)]
                       v
                       (throw (ex-info (str "Can't resolve param " param) {:msg msg :aliases aliases :param param})))))]
      (if-some [f (encoding msg)]
        (f lookup msg)
        (throw (ex-info "Can't serialize message, unknown or missing encoding tag." {:msg msg}))))))
