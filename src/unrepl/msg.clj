(ns unrepl.msg
  "This namespace is meant for clients: it composes messages according to the description sent by the server and the avilable data."
  (require [unrepl.print :as p]))

(defn- param? [x]
  (and (tagged-literal? x) (= 'unrepl/param (:tag x))))

(def ^:dynamic *encoding-printers* {'unrepl/raw (fn [lookup]
                                                  (letfn [(raw-printer [w form p]
                                                            (cond
                                                              (vector? form) (reduce #(doto %1 (raw-printer %2 p)) w form)
                                                              (char? form) (p/write w (str form))
                                                              (string? form) (p/write w form)
                                                              (param? form) (p/write w (str (lookup form)))
                                                              (encoding? form) (p form)))]
                                                    (fn [w {:keys [form]} p]
                                                      (raw-printer w form p))))
                                    'unrepl/edn (fn [lookup]
                                                  (fn [w {:keys [form]} p] 
                                                    (binding 
                                                      [p/*tagged-literal-printers*
                                                       (assoc p/*tagged-literal-printers* 'unrepl/param (fn [w x p] (p (lookup form))))]
                                                      (p form))))})

(defn- encoding? [x]
  (and (tagged-literal? x) (contains? *encoding-printers* (:tag x))))

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
      (binding [*print-length* Long/MAX_VALUE
                *print-level* Long/MAX_VALUE
                p/*tagged-literal-printers*
                (into p/*tagged-literal-printers* (map (fn [[sym f]] [sym (f lookup)])) *encoding-printers*)]
        (when-not (encoding? msg)
          (throw (ex-info "Can't serialize message, unknown or missing encoding tag." {:msg msg})))
        (p/edn-str msg)))))