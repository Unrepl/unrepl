# unrepl

A REPL-centric approach to tooling.

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

The input is expected to be free form (a character stream).

The output is a stream of EDN datastructures.

To be more precise it's a stream of 2/3-item tuples:

1. the first component is a tag (keyword)
2. the second id the payload
3. (optional) a group id

The purpose of the tag is to allow demultiplexing things that are usually intermingled in a repl display.

Five tags are defined at the moment: `:prompt`, `:eval`, `:out`, `:err` and `:exception`.

| Tag | Payload |
|-----|---------|
|:prompt|A map of qualified symbols (var names) to their values|
|:eval|The evaluation result|
|:out|A string|
|:err|A string|
|:exception|The exception|

### Machine printing
Pretty printing is meant for humans and should be performed on the client.

Clojure values are machine-printed to EDN.

#### Ellipsis or elisions

Printing should be bound in length and depth. When the printer decides to elidea sequence of values it should emit a tagge literal `#unrepl/... m` where `m` is either `nil` or a map. This map may contain a `:get` key associated to a string. All simple (non qualified) keywords (and those with `unrepl` namespace) are reserved for future revisions of these specification.

Example: machine printing `(range)`

```clj
(0 1 2 3 4 5 6 7 8 9 #ednrepl/... {:get "(tmp1234/get :G__8391)"})
```

(The `:get` value being a string is due to the fact that the input stream is not constrained.)

These maps may also have an `:id` key to keep elided values different when used in sets or as keys in maps. So either each elision get a unique id or the id may be value-based (that is: when two elisions ids are equal their elided values are equal). When `:get` is provided there's no need for `:id` (because by definition the `:get` value will be unique or at least value-based).

Example: printing the set `#{[1] [2]}` with a very shallow print depth and a (broken) printer that doesn't assign `:id` nor `:get` returns:

```clj
#{[#ednrepl/... nil] [#ednrepl/... nil]}
```

which is not readable. Hence the necessity of `:id` or `:get` to provide unique ids.

Clients may render a `#unrepl/... {}` literal as `...` and when `:get` is present offers the user the ability to expand this elision.

To expand the elision the client send to the repl the value associated to the `:get` key. The repl answers (in the `:eval` channel)  with either:

 * a collection that should be spliced in lieu of the `...`
 * a `#unrepl/...` value with no `:get` key (for example when the elided values are not reachable anymore), including (but not limited to) `#unrepl/... nil`.

Elided maps representations must still have an even number of entries, so a second elision marker `#unrepl/... nil` is added to pad the representation. All data (if any) is supported by the elision in key position. When splicing the expansion both markers are replaced.

## License

Copyright © 2017 Christophe Grand

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
