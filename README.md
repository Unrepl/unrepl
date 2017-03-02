# unrepl

A REPL-centric approach to tooling.

## Background

Imagine a protocol so flexible that you can upgrade it to anything you want.

This protocol exists it's a REPL. A standard repl (clojure.main or the socket repl) are not perfect for tooling but they provide a common minimal ground: an input and output streams of characters. Both can be hijacked to install your own handler, including another REPL better suited for its client.

REPL: the ultimate content negotiation protocol!

The present repository suggests representations for machine-to-machine REPLs and provides a reference implementation.

## Usage

## Spec

The input is expected to be free form.

The output is a stream of EDN datastructures.

### Machine printing
Pretty printing is meant for humans and should be performed on the client.

Clojure values are machine-printed to EDN.

Printing should be bound in length and depth. When the printer decides to elidea sequence of values it should emit a tagge literal `#unrepl/... m` where `m` is either `nil` or a map. This map may contain a `:get` key associated to a string. All simple (non qualified) keywords (and those with `unrepl` namespace) are reserved for future revisions of these specification.

Clients may render a `#unrepl/... {}` literal as `...` and when `:get` is present offers the user the ability to expand this elision.

To expand the elision the client send to the repl the value associated to the `:get` key. The repl answers (in the `:eval` channel)  with either:

 * a collection that should be spliced in lieu of the `...`
 * a `#unrepl/...` value with no `:get` key (for example when the elided values are not reachable anymore), including (but not limited to) `#unrepl/... nil`.

Elided maps representations must still have an even number of entries, so a second elision marker `#unrepl/... nil` is added to pad the representation. All data (if any) is supported by the elision in key position. When splicing the expansion both markers are replaced.

## License

Copyright © 2017 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
