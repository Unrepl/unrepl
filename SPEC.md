# unrepl specification

## Status

This document is a work in progress but is mostly stable at this point (few breakings change to expect). A companion implementation is available in the `unrepl.repl` namespace.

You can ask questions and share feedback on the `#unrepl` channel on the Clojurians Slack.

## Breaking Changes

2017-11-23: change in map elisions, now the key is always `#unrepl/... nil` and the value contains the actual elision.

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

Ten core tags are defined: `:unrepl/hello`, `:prompt`, `:read`, `:started-eval`, `:eval`, `:out`, `:err`, `:log`, and `:exception`. More tags are defined in standard [actions](#actions).

| Tag | Payload |
|-----|---------|
|`:unrepl/hello`|A map or nil|
|`:prompt`|A map or nil|
|`:read` | A map |
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

The hello map may also have a `:session` key which is just an identifier (any type) allowing a client to recognize a session it has already visited.

The hello map may also have a `:about` key mapped to a map. The intent of the `:about` map is to contain information about the REPL implementation, supported language, running environment (VM, OS etc.).

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

#### `:prompt`

The payload provides general information about the unrepl session, covering two topics:

 * Information about the current input state.
 * Qualified symbols (var names) mapped to their respective values.

e.g.

```clj
[:prompt {:file "unrepl-session", :line 1, :column 1, :offset 0, clojure.core/*warn-on-reflection* nil, clojure.core/*ns* #unrepl/ns user}]
```

Where `:offset` is the number of characters (well UTF-16 code units) from the start of the unrepl session. *Line-delimiting sequences are normalized to one character* (`\n`) -- so if the client sends a `CRLF` the offset is only increased by 1.

#### `:read`

Similar to `:prompt`, `:read` is meant to help tools to relate outputs to inputs by providing information regarding the latest stream sent to the reader. It can be especially useful when several forms are sent in a batch or when syntax errors happen and the reader resumes reading.

```clj
[:read {:from [line col] :to [line col] :offset N :len N} 1]
```

`:offset` works exactly as in `:prompt`.

### Machine printing
Pretty printing is meant for humans and should be performed on the client.

Clojure values are machine-printed to EDN.

#### Filling the gap

 * Vars (e.g. `#'map`) are printed as `#clojure/var clojure.core/map`.
 * Ratios (e.g. `4/3`) are printed as `#unrepl/ratio [4 3]`.
 * Classes are printed as `#unrepl.java/class ClassName` or `#unrepl.java/class [ClassName]` for arrays (with no bounds on the nesting).
 * Namespaces are printed as `#unrepl/ns name.sp.ace`.
 * Metadata is printed as `#unrepl/meta [{meta data} value]`.
 * Patterns (regexes) are printed as `#unrepl/pattern "[0-9]+"`.
 * Objects are printed as `#unrepl/object [class "id" representation]`. The representation is implementation dependent. One may use an elided map representation to allow browsing the object graph.

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

##### Long strings
Strings too long should be cut off by the printer. In which case `#unrepl/string [prefix #unrepl/... m]` is emitted with prefix being an actual prefix of the cut off repl with the following restriction: the cut can't occur in the middle of a surrogate pair; this restriction only holds for well-formed strings.

##### Caveats
###### Position
The elision should always be at the end of the collection.

###### Padding maps
Elided maps representations must still have an even number of entries, so a second elision marker `#unrepl/... nil` is added *as key* to pad the representation. All data (if any) is supported by the elision in value position. When splicing the expansion both markers are replaced.

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
- `:content` optional base64-encoded string (e.g. `#unrepl/base64 "..."`), or an elision.

### Message Templates

A message template is an executable description of the expected message. It's a parametrized edn form: all keywords tagged by `#unrepl/param` are to be substituted by their value. The resulting form is serialized as edn and sent to a repl.

### Actions

All actions are optional.

#### Session actions

(Advertised in `:unrepl/hello` messages.)

##### `:set-source`

Three parameters: 

```clj
(spec/def :unrepl/filename string?)
(spec/def :unrepl/line integer?)
(spec/def :unrepl/column integer?)
```

Sets the filename, line and column numbers for subsequent evaluations. The change will take effect at next prompt display.

##### `:print-limits`

Set print limits (pass `nil` to leave a limit unchanged). Returns a map of param names to original values.

##### `:start-aux`

Upgrades another connection as an auxilliary (for tooling purpose) unREPL session.

##### `:unrepl.jvm/start-side-loader`

Upgrades the control REPL where it is issued to a sideloading session.

When a sideloading session is started, the JVM will ask the client for classes or resources it does not have. Basically, this allows the extension of the classpath.

A sideloading session is a very simple edn-protocol.

It's a sequence of request/responses initiated by the server: the client waits for messages `[:resource "resource/name"]` or `[:class "some.class.name"]` and replies either `nil` or a base64-encoded string representation of the file.

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

## License

Copyright © 2017 Christophe Grand

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
