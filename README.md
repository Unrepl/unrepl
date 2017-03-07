# unrepl

A REPL-centric approach to tooling.

## Status

This document is a work in progress. A companion implementation is available in the `unrepl.repl` namespace.

Use `(unrepl.repl/start)` to start an unrepl insde a regular repl. Type `exit` to exit back to the original repl.

## Background

Imagine a protocol so flexible that you can upgrade it to anything you want.

This protocol exists it's a REPL. A standard repl (clojure.main or the socket repl) are not perfect for tooling but they provide a common minimal ground: an input and output streams of characters. Both can be hijacked to install your own handler, including another REPL better suited for its client.

REPL: the ultimate content negotiation protocol!

The present repository suggests representations for machine-to-machine REPLs and provides a reference implementation.

## Usage

## Spec

### Reserved keywords

All simple (not qualified) keywords and all keywords in the `unrepl` namespaces are reserved.

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

This is how an unrepl implementtaion advertises its capabilities: by listing them along a machine-readable specification of the message to send to trigger them.

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
< [:eval :ciao]
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

Some values may print to `#unrepl/mime m` where m is a map with keys: `:content-type` (optional, string, defaults to "application/binary"), `:content-length` (optional, number), `:filename` (optional, string), `:details` (optional, anything, a representation of the object (eg for a `java.io.File` instance it could be the path and the class)), `:content` (optional base64-encoded) and `:get` (string).

### Message Templates

TBD

## License

Copyright © 2017 Christophe Grand

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
