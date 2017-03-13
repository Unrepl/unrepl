# unrepl

A REPL-centric approach to tooling.

unrepl is a general purpose stream-based REPL protocol.

An unrepl repl is just a REPL with a fancy printer.

## Status

This document is a work in progress. A companion implementation is available in the `unrepl.repl` namespace.

The output (edn messages) & input specification (message templates) is mostly done. What is left to specify is:

 * some standard (but optional) commands

 * some parameters.

You can ask questions and share feedback on the `#unrepl` channel on the Clojurians Slack.

## Background

Imagine a protocol so flexible that you can upgrade it to anything you want.

This protocol exists it's a REPL. A standard repl (clojure.main or the socket repl) are not perfect for tooling but they provide a common minimal ground: an input and output streams of characters. Both can be hijacked to install your own handler, including another REPL better suited for its client.

REPL: the ultimate content negotiation protocol!

The present repository suggests representations for machine-to-machine REPLs and provides a reference implementation.

A REPL by nature is a very sequential process: it reads then evals, then prints and then starts over. One REPL = One thread. Concurrency is achieved by having several REPLs.

A REPL is also stateful, it is a connected protocol, so the context doesn't need to be transferred constantly.

A REPL is meant for evaluating code.

It follows that some tooling needs (e.g. autocompletion) may be better serviced by a separate connection which may not be a REPL (but may have started as a REPL upgraded to something else).

## Usage

`lein run -m unrepl.repl/start` at the commande line or `(unrepl.repl/start)` to start an unrepl inside a regular repl. Type `^D` to exit back to the original repl. (Actually, in a term, type `^V^D` to prevent it from interpreting `^D`; if you know how to enter control chars with `rlwrap` let me know!) 

## Spec

### Reserved keywords and extensions

All simple (not qualified) keywords, the `unrepl` namespace and all namespaces starting by `unrepl.` are reserved.

This protocol is designed to be extended, extensions just have to be namespaced and designed in a way that a client can ignore messages from unknown extensions.

### Streams format

The input is expected to be free form (a character stream). The default behavior to be expected is evaluation. (It's a rEpl after all.)

The output is a stream of EDN datastructures.

To be more precise it's a stream of 2/3-item tuples:

1. the first component is a tag (keyword)
2. the second id the payload
3. (optional) a group id (meant to group together messages)

The purpose of the tag is to allow demultiplexing things that are usually intermingled in a repl display.

Eight core tags are defined: `:unrepl/hello`, `:bye`, `:prompt`, `:eval`, `:command`, `:out`, `:err` and `:exception`. More tags are defined in standard commands.

| Tag | Payload |
|-----|---------|
|`:unrepl/hello`|A map|
|`:bye`|`nil`|
|`:prompt`|A map of qualified symbols (var names) to their values|
|`:eval`|The evaluation result|
|`:command`|The command result|
|`:out`|A string|
|`:err`|A string|
|`:exception`|The exception|

Messages not understood by a client should be ignored.

#### `:unrepl/hello`

The first message must be a `:unrepl/hello`. It's the only message whose tag is qualified. It's namespaced to make sniffing the protocol easier. (For example when connecting to a socket you may either get an existing unrepl repl or a standard repl that you are going to upgrade.)

Its payload is a map which may have a `:commands` key mapping to a map of command ids (keywords) to template messages.

This is how an unrepl implementation advertises its capabilities: by listing them along a machine-readable specification of the message to send to trigger them.

The hello map may also have a `:session` key which is just an identifier (any type) allowing a client to recognize a session it has already visited (eg when getting a `:unrepl/hello` after a `:bye`).

The hello map may also have a `:about` key mapped to a map. The intent of this map is to contain information about the REPL implementation, supported language, running environment (VM, OS etc.).

#### `:bye`

The `:bye` message must be the last unrepl message before yielding control of the input and output streams (eg nesting another REPL... or [Eliza](https://en.wikipedia.org/wiki/ELIZA)).

Implementation note: this can be detected when the expression being evaluated tries to read from the input. When evaluation returns, the unrepl impl can reassume control of the input and output stream. If it does so, its first message must be a `:unrepl/hello`.

Example:

```clj
< [:prompt {:ns #object[clojure.lang.Namespace 0x2d352c62 "unrepl.core"], :*warn-on-reflection* nil}]
> (loop [] (let [c (char (.read *in*))] (case c \# :ciao (do (println "RAW" c) (recur)))))
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
< [:unrepl/hello {:commands {}}]
< [:eval :ciao 1]
< [:prompt {:ns #object[clojure.lang.Namespace 0x2d352c62 "unrepl.core"], :*warn-on-reflection* nil}]
```

### Machine printing
Pretty printing is meant for humans and should be performed on the client.

Clojure values are machine-printed to EDN.

#### Filling the gap

Ratios (e.g. `4/3`) are printed as `#unrepl/ratio [4 3]`

Classes are printed as `#unrepl.java/class ClassName` or `#unrepl.java/class [ClassName]` for arrays (with no bounds on the nesting).

Namespaces are printed as `#unrepl/ns name.sp.ace`.

Metadata is preinted as `#unrepl/meta [{meta data} value]`.

Objects are printed as `#unrepl/object [class "id" representation]`. The representation is implementation dependent. One may use an elided map representation to allow browsing the object graph.

#### Ellipsis or elisions

Printing should be bound in length and depth. When the printer decides to elidea sequence of values it should emit a tagge literal `#unrepl/... m` where `m` is either `nil` or a map. This map may contain a `:get` key associated to a template message. All simple (non qualified) keywords (and those with `unrepl` namespace) are reserved for future revisions of these specification.

Example: machine printing `(range)`

```clj
(0 1 2 3 4 5 6 7 8 9 #unrepl/... {:get #unrepl/raw "(tmp1234/get :G__8391)"})
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
< (0 1 2 3 4 5 6 7 8 9 #unrepl/... {:get #unrepl/raw "(tmp1234/get :G__8391)"})
> (tmp1234/get :G__8391)"
< (10 11 12 13 14 15 16 17 18 19 #unrepl/... {:get #unrepl/raw "(tmp1234/get :G__8404)"})
```

##### Caveats
###### Padding maps
Elided maps representations must still have an even number of entries, so a second elision marker `#unrepl/... nil` is added to pad the representation. All data (if any) is supported by the elision in key position. When splicing the expansion both markers are replaced.

###### Identity and value
These maps may also have an `:id` key to keep elided values different when used in sets or as keys in maps. So either each elision get a unique id or the id may be value-based (that is: when two elisions ids are equal their elided values are equal). When `:get` is provided there's no need for `:id` (because by definition the `:get` value will be unique or at least value-based).

Example: printing the set `#{[1] [2]}` with a very shallow print depth and a (broken) printer that doesn't assign `:id` nor `:get` returns:

```clj
#{[#unrepl/... nil] [#unrepl/... nil]}
```

which is not readable. Hence the necessity of `:id` or `:get` to provide unique ids.

#### MIME Attachments

Some values may print to `#unrepl/mime m` where m is a map with keys: `:content-type` (optional, string, defaults to "application/octet-stream"), `:content-length` (optional, number), `:filename` (optional, string), `:details` (optional, anything, a representation of the object (eg for a `java.io.File` instance it could be the path and the class)), `:content` (optional base64-encoded) and `:get` (message template).

### Message Templates

A message template is an executable description of the expected message.

They always start by a tagged-literal named an encoding. This document specifies two encodings: `unrepl/raw` and `unrepl/edn`.

Qualified keywords tagged by `unrepl/param` are to be substituted by their value.

#### `unrepl/edn` encoding

The form is left intact except for `#unrepl/param :some/param` that are replaced by their value. The resulting form is serialized as edn.

#### `unrepl/raw` encoding

When the form is a character or a string, it is send as is.
When the form is a vector, its components are recursively visited -- thus it denotes concatenation.
When the form is an encoding, it's serialized according to this encoding.

#### Examples

A very simple one:

```clj
#unrepl/raw \0003 ; no param always write ^C
```

A composite one:

```clj
#unrepl/raw [\u0010 #unrepl/edn (set-file-line-col #unrepl/param :unrepl/sourcename #unrepl/param :unrepl/line #unrepl/param :unrepl/col)]
```

There's a helper client namespace (`unrepl.client`) to compose messages from a message template and a map of parameters:

```clj
=> (unrepl.client/msg-str 
     (clojure.edn/read-string {:default tagged-literal}
     "#unrepl/raw [\\u0010 #unrepl/edn (set-file-line-col #unrepl/param :unrepl/sourcename #unrepl/param :unrepl/line #unrepl/param :unrepl/col)]")
     {:unrepl/sourcename "demo.clj"
      :unrepl/line 12
      :unrepl/col 36})

"\u0010(set-file-line-col \"demo.clj\" 12 36)"
```

### Commands

All commands are optional.

The `:interrupt` and `:background-current-eval` commands are kind of special because they require reading while evaluation is going on. To preserve the single-thread model of a REPL care should be taken to not use the reader to recognize these commands have been issued. 

#### `:exit`

No parameter. Exit the repl, close the connection.

#### `:interrupt`

No parameter. Aborts the current running evaluation. Upon success a `[:interrupted nil id]` message is written (where `id` is the group id (if any) of the current evaluation).

#### `:background-current-eval`

No parameter. Transforms the current running evaluation in a Future. Upon success a `[:eval future id]` message is written (where `id` is the group id (if any) of the current evaluation).

Upon completion of the future no message is sent.

#### `:set-source`

Three parameters: `:unrepl/filename` (string), `:unrepl/line` (integer) and `:unrepl/command` (integer).

Sets the filename, line and column numbers for subsequent evaluations. The reader will update the line and column numbers as it reads more input. 

#### `:unrepl.jvm/enable-sideloader`

No parameter. Installs a classloader that asks the client for classes and resources when not found on the server.

So the client may be able to reply at any message `[:unrepl.jvm/find-class classname]` where classname is a string. The server expects either nil (not found) or the class/resource as a Base64-encoded string.


## License

Copyright © 2017 Christophe Grand

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
