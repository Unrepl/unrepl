# unrepl

A REPL-centric approach to tooling, by means of a general purpose stream-based REPL protocol.

Unrepl is the common base for a set of REPL protocols.  It is meant as an upgrade-path from a more basic [nREPL](https://nrepl.xyz) or [Socket REPL](https://clojure.org/reference/repl_and_main#_launching_a_socket_server), and allows different Unrepl-based clients to implement their own extensions to the REPL protocol (e.g. for IDE integration).  Such Unrepl-based clients would send their own server payload ("blob") through the basic REPL connection into the target process, to upgrade the server side of this connection with the desired features.  After this upgrade, the client could use the new features on the existing REPL connection.

The benefit of this process is, that the target process does not need to include REPL code beyond Socket REPL, which is already included in Clojure 1.8+.  Everything else is loaded only when needed and can be extended according to the needs of the client.  Due to the shared common base, it should be easy to share parts of the server implementation between different Unrepl derivatives.

Thus Unrepl is intended for toolsmiths and not something a regular user will usually come in direct contact with.  Assuming someone uses "MyIDE", they would setup a "MyIDE REPL" connection to their program from the "MyIDE" UI, and "MyIDE" would transparently upgrade the REPL connection to something they could brand as the "MyIDE REPL" experience, without the user noticing that something like Unrepl even exists.

Unrepl is really a meant as a foundation for derivative private works used by clients.

## What's "the blob"?

The blob is a piece of clojure code sent to bootstrap an unrepl implementation. It's expected to be static and opaque.

## Why this hypermedia nonsense if the unrepl implementation is private to the client?

Well it decouples the client and its unrepl implementation, making it easier for the client maintainer to reuse or share code of their server implementation with other tools maintainers.

Furthermore if you start considering that a client may ship several blobs (eg one for Clojure, one for Clojurescript) then it allows the client to behave properly independently on the nature of the endpoint. 

## Usage

If you are a simple user, you don't need to care about unrepl proper, not even add it to your project deps. Just use one of the existing clients:

 * [Unravel](https://github.com/Unrepl/unravel) a command-line client,
 * [Spiral](https://github.com/Unrepl/spiral) an Emacs one.
 * [Vimpire](https://bitbucket.org/kotarak/vimpire) ([git](https://github.com/kotarak/vimpire)) for Vim

If you want to develop a client or just understand better what's happening under the hood then try:

```sh
git clone https://github.com/Unrepl/unrepl.git
cd unrepl
# start a plain repl (non unrepl, non nrepl), so if you are in a lein project:
java -cp `lein classpath` -Dclojure.server.repl="{:port 5555,:accept clojure.core.server/repl,:server-daemon false}" clojure.main -e nil &
# generate the blob
clj -m unrepl/make-blob
# connect, upgrade and enjoy!
rlwrap cat resources/unrepl/blob.clj - | nc localhost 5555
```

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

