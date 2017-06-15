# unrepl

A REPL-centric approach to tooling.

unrepl is a general purpose stream-based REPL protocol.

An unrepl repl is just a REPL with a fancy printer.

## Status

This document is a work in progress. A companion implementation is available in the `unrepl.repl` namespace.

The output (edn messages) & input specification ([message templates](#message-templates)) is mostly done. What is left to specify is:

 * more standard (but optional) actions
 * some parameters.

You can ask questions and share feedback on the `#unrepl` channel on the Clojurians Slack.

## Background

Imagine a protocol so flexible that you can upgrade it to anything you want.

This protocol exists, it's a REPL. A standard repl (clojure.main or the socket repl) is not perfect for tooling but it provides a common minimal ground: an input and output streams of characters. Both can be hijacked to install your own handler, including another REPL better suited for its client.

REPL: the ultimate content negotiation protocol!

The present repository suggests representations for machine-to-machine REPLs and provides a reference implementation.

A REPL is, by nature, a very sequential process: it reads, then evals, then prints, and then starts over. One REPL = One thread. Concurrency is achieved by having several REPLs.

A REPL is also stateful, it is a connected protocol, so the context doesn't need to be transferred constantly.

A REPL is meant for evaluating code.

Some tooling needs (e.g. autocompletion) may be better serviced by a separate connection, which should not necessarily be a REPL (but may have started as a REPL upgraded to something else.)

Parts of this specification assumes two REPLs: the main (or user) REPL and the control (or client) REPL.

## Usage

`lein run -m unrepl.repl/start` at the command line or `(unrepl.repl/start)` to start an unrepl inside a regular repl.

## Spec

### Reserved keywords and extensions

All simple (not qualified) keywords, the `unrepl` namespace, and all namespaces starting by `unrepl.` are reserved.

This protocol is designed to be extended, extensions just have to be namespaced and designed in a way that a client can ignore messages from unknown extensions.

### Streams format

The input is expected to be free form (a character stream)

The output is a stream of EDN datastructures.

To be more precise it's a stream of 2/3-item tuples, e.g. `[:read {:some :payload} 1]`, where:

1. First component is a tag (keyword). Its purpose is to allow demultiplexing things that are usually intermingled in a repl display.
2. Second component is the payload.
3. Third (optional) component is a group id, meant to group together messages.

Eight core tags are defined: `:unrepl/hello`, `:bye`, `:prompt`, `:started-eval`, `:eval`, `:out`, `:err` and `:exception`. More tags are defined in standard [actions](#actions).

| Tag | Payload |
|-----|---------|
|`:unrepl/hello`|A map or nil|
|`:bye`|A map or nil|
|`:prompt`|A map of qualified symbols (var names) to their values|
|`:started-eval`|A map or nil|
|`:eval`|The evaluation result|
|`:out`|A string|
|`:err`|A string|
|`:log`|A log vector|
|`:exception`|A map|

Messages not understood by a client should be ignored.

#### `:unrepl/hello`

The first message must be a `:unrepl/hello`. It's the only message whose tag is qualified. It's namespaced to make sniffing the protocol easier. For example, when connecting to a socket you may either get an existing unrepl repl or a standard repl that you are going to upgrade.

Its payload is a map which may have a `:actions` key mapping to another map of [action ids](#actions) (keywords) to [template messages](#message-templates). All those actions should be specific to the session.

This is how an unrepl implementation advertises its capabilities: by listing them along a machine-readable specification of the message needed to be sent to trigger them.

The hello map may also have a `:session` key which is just an identifier (any type) allowing a client to recognize a session it has already visited (e.g. when getting a `:unrepl/hello` after a `:bye`).

The hello map may also have a `:about` key mapped to a map. The intent of the `:about:` map is to contain information about the REPL implementation, supported language, running environment (VM, OS etc.).

#### `:bye`

The `:bye` message must be the last unrepl message before yielding control of the input and output streams (eg nesting another REPL... or [Eliza](https://en.wikipedia.org/wiki/ELIZA)).

Implementation note: this can be detected when the expression being evaluated tries to read from the input. When evaluation returns, the unrepl impl can reassume control of the input and output stream. If it does so, its first message must be a `:unrepl/hello`.

Its payload is a map.

```clj
(spec/def :unrepl/bye-payload
  (spec/keys :opt-un [:unrepl.bye/reason :unrepl.bye/outs :unrepl/actions]))

(spec/def :unrepl.bye/reason #{:disconnection :upgrade})

;; describes what happen to background outputs after the `:bye` message: 
(spec/def :unrepl.bye/outs
  #{:muted ; they are muted (think `/dev/null`) 
    :blocked ; writing threads are blocked
    :closed ; they are closed (unless handled, the IO exception kills the writer)
    :cobbled}) ; everything is cobbled together (like with a plain repl) 
```


Example:

```clj
< [:prompt {:ns #object[clojure.lang.Namespace 0x2d352c62 "unrepl.core"], :*warn-on-reflection* nil}]
> (loop [] (let [c (char (.read *in*))] (case c \# :ciao (do (println "RAW" c) (recur)))))
< [:started-eval {} 1]
< [:bye nil]
> A
< RAW A
< RAW 
<
> ABC
< RAW A
< RAW B
< RAW C
< RAW 
< 
> # 
< [:unrepl/hello {:actions {}}]
< [:eval :ciao 1]
< [:prompt {:ns #object[clojure.lang.Namespace 0x2d352c62 "unrepl.core"], :*warn-on-reflection* nil}]
```

#### `:exception`

The payload is a map with a required key `:ex` which maps to the exception, and a second optional key `:phase` which can take 5 values:

 * `:unknown`, (default) no indication on the source of the exception.
 * `:read`, the exception occured during `read` and is more likely a syntax error. (May be an IO or any exception when `*read-eval*` is used.)
 * `:eval`, the exception occured during `eval`.
 * `:print`, the exception occured during `print`.
 * `:repl`, the exception occured in the repl code itself, fill an issue.

#### `:log`

```clj
(spec/def :unrepl/log-msg
  (spec/cat :level keyword? :key string? :inst inst? :args (spec/* any?)))
```

The arguments will be machine-printed and as such could be elided.

#### `:read`

`:read` is meant to help tools to relate outputs to inputs. It can be especially useful when several forms are sent in a batch or when syntax errors happen and the reader resumes reading.

```clj
[:read {:start [line col] :end [line col] :offset N :len N} 1]
```

Offset is the number of characters (well UTF-16 code units) from the start of the unrepl session. *Line-delimiting sequences are normalized to one character* (`\n`) -- so if the client sends a `CRLF` the offset is only increased by 1.


### Machine printing
Pretty printing is meant for humans and should be performed on the client.

Clojure values are machine-printed to EDN.

#### Filling the gap

- Ratios (e.g. `4/3`) are printed as `#unrepl/ratio [4 3]`.
- Classes are printed as `#unrepl.java/class ClassName` or `#unrepl.java/class [ClassName]` for arrays (with no bounds on the nesting).
- Namespaces are printed as `#unrepl/ns name.sp.ace`.
- Metadata is printed as `#unrepl/meta [{meta data} value]`.
- Patterns (regexes) are printed as `#unrepl/pattern "[0-9]+"`.
- Objects are printed as `#unrepl/object [class "id" representation]`. The representation is implementation dependent. One may use an elided map representation to allow browsing the object graph.

#### Ellipsis or elisions

Printing should be bound in length and depth. When the printer decides to elide a sequence of values, it should emit a tagged literal `#unrepl/... m`, where `m` is either `nil` or a map. This map may contain a `:get` key associated to a [template message](#message-templates). All simple (non qualified) keywords (and those with `unrepl` namespace) are reserved for future revisions of these specification.

Example: machine printing `(range)`

```clj
(0 1 2 3 4 5 6 7 8 9 #unrepl/... {:get (tmp1234/get :G__8391)})
```

##### Rendering
Clients may render a `#unrepl/... {}` literal as `...` and when `:get` is present offers the user the ability to expand this elision.

##### Expansion
To expand the elision the client send to the repl the value associated to the `:get` key. The repl answers (in the `:eval` channel)  with either:

 * a collection that should be spliced in lieu of the `...`
 * a `#unrepl/...` value with no `:get` key (for example when the elided values are not reachable anymore), including (but not limited to) `#unrepl/... nil`.

So continuing the `(range)` example:

```clj
> (range)
< (0 1 2 3 4 5 6 7 8 9 #unrepl/... {:get (tmp1234/get :G__8391)})
> (tmp1234/get :G__8391)
< (10 11 12 13 14 15 16 17 18 19 #unrepl/... {:get (tmp1234/get :G__8404)})
```

##### Caveats
###### Padding maps
Elided maps representations must still have an even number of entries, so a second elision marker `#unrepl/... nil` is added to pad the representation. All data (if any) is supported by the elision in key position. When splicing the expansion both markers are replaced.

###### Identity and value
These maps may also have an `:id` key to keep elided values different when used in sets or as keys in maps. So either each elision get a unique id or the id may be value-based (that is: when two elisions ids are equal, their elided values are equal). When `:get` is provided there's no need for `:id` (because by definition the `:get` value will be unique or at least value-based).

Example: printing the set `#{[1] [2]}` with a very shallow print depth and a (broken) printer that doesn't assign `:id` nor `:get` returns:

```clj
#{[#unrepl/... nil] [#unrepl/... nil]}
```

which is not readable. Hence the necessity of `:id` or `:get` to provide unique ids.

#### Lazy-seq errors

When realization of a lazy sequence throws an exception, the exception is inlined in the sequence representation and tagged with `unrepl/lazy-error`.

For example, the value of `(map #(/ %) (iterate dec 3))` prints as:

```clj
(#unrepl/ratio [1 3] #unrepl/ratio [1 2] 1 #unrepl/lazy-error #error {:cause "Divide by zero", :via [{:type #unrepl.java/class java.lang.ArithmeticException, :message "Divide by zero", :at #unrepl/object [#unrepl.java/class java.lang.StackTraceElement "0x272298a" "clojure.lang.Numbers.divide(Numbers.java:158)"]}], :trace [#unrepl/... nil]})
```

#### MIME Attachments

Some values may print to `#unrepl/mime m` where m is a map with keys:

- `:content-type`: optional, string, defaults to "application/octet-stream".
- `:content-length`: optional, number.
- `:filename`: optional, string.
- `:details`: optional, anything, a representation of the object (e.g. for a `java.io.File` instance it could be the path and the class).
- `:content` optional base64-encoded.
- `:get` message template.

### Message Templates

A message template is an executable description of the expected message. It's a parametrized edn form: all keywords tagged by `#unrepl/param` are to be substituted by their value. The resulting form is serialized as edn and sent to a repl.

### Actions

All actions are optional.

#### Session actions

(Advertised in `:unrepl/hello` messages.)

##### `:exit`

No parameter. Exit the repl, close the connection.

##### `:set-source`

Three parameters: 

```clj
(spec/def :unrepl/filename string?)
(spec/def :unrepl/line integer?)
(spec/def :unrepl/column integer?)
```

Sets the filename, line and column numbers for subsequent evaluations. The change will take effect at next prompt display.

##### `:unrepl.jvm/start-side-loader`

Upgrades the control REPL where it is issued to a sideloading session.

When a sideloading session is started, the JVM will ask the client for classes or resources it does not have. Basically, this allows the extension of the classpath.

A sideloading session is a very simple edn-protocol.

It starts by `[:unrepl.jvm/sideloader]` preamble and a then a serie of request/responses initiatted by the server: the client waits for messages `[:find-resource "resource/name"]` or `[:find-class "some.class.name"]` and replies either `nil` or a base64-encoded string representation of the file.

The only way to terminate a sideloading session is to close the connection.

##### `:log-eval` and `:log-all`

No parameters.

`:log-eval` returns a function of one argument (`msg` conforming to `:unrepl/log-msg`) that will print `[:log msg group-id]` only when called (directly or not) from evaluated code.

`:log-all` returns a function of one argument (`msg` conforming to `:unrepl/log-msg`) that will print `[:log msg nil]`.

Client software should use these values to hook up appenders for the user log facilities. For example, assuming `Timbre` as the logging library and a value of `(clojure.core/some-> :session329  unrepl.repl/session :log-eval)]` for `:log-eval` then the client can send this form to the repl (main or control):

```clj
(let [w (clojure.core/some-> :session329  unrepl.repl/session :log-eval)]
  (timbre/merge-config!
    {:appenders
     {:println {:enabled? false} ; disabled because it tries to force print lazyseqs
      :unrepl
      {:enabled? true
       :fn (fn [{:keys [level instant ?ns-str vargs]}]
             (w (into [level ?ns-str instant] vargs)))}}}))
```

(Namespaces have been omitted or aliased, however this form should be built using syntax-quote to ensure proper qualification of symbols.)

Once the above expression evaluated, we have the following interactions:

```clj
(timbre/log :info "a" (range))
[:read {:from [14 1], :to [15 1], :offset 342, :len 31} 12]
[:started-eval {:actions {:interrupt (unrepl.repl/interrupt! :session329 12), :background (unrepl.repl/background! :session329 12)}} 12]
[:log [:info "user" #inst "2017-04-04T14:56:56.574-00:00" "a" (0 1 2 3 4 5 6 7 8 9 #unrepl/... {:get (unrepl.repl/fetch :G__3948)})] 12]
[:eval nil 12]
```

Hence a client UI can render log messages as navigable.

#### Eval actions
(Advertised in `:started-eval` messages.)

##### `:interrupt`

No parameter. Aborts the current running evaluation. Upon success a `[:interrupted nil id]` message is written (where `id` is the group id (if any) of the current evaluation).

##### `:background`

No parameter. Transforms the current running evaluation in a Future. Upon success returns true (to the control repl) and the evaluation (in the main repl) immediatly returns `[:eval a-future id]`.

Upon completion of the future a `[:bg-eval value id]` is sent (on the main repl).

#### Bye actions
(Advertised in `:bye` messages.)

##### `:reattach-outs`

No parameter.

Redirects all outs to the repl (unrepl or not) in which the action has been issued. 

##### `:set-mute-mode`

__DEPRECATED__

By default all spurious output is blocked after a `:bye` message.

Parameter:

```clj
(spec/def :unrepl/mute-mode #{:block :mute :redirect})
```

Returns true on success.

This actions expects a parameter `:unrepl/mute-mode` which can be one of:

 * `:block` (default behavior),
 * `:mute` (aka `/dev/null`),
 * `:redirect` which redirects all outs to the control repl in which the action has been issued. 

## License

Copyright © 2017 Christophe Grand

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
