# How to upgrade a repl to unrepl 

(Audience: repl client authors)

Once you have a connection to a REPL, you should send the content of the resource file `unrepl/upgrade/blob.clj` to it.

Once sent, start reading the output and wait for vectors. Here are the vectors you may get:
 * `[:unrepl.upgrade/require some.ns.name :some/thing]` then send the content of the ns identified by `some.ns.name` to the repl, followed by `:some/thing` to delimit the end of the ns.
 * `[:unrepl.upgrade/failed "pr-str of an exception"]` something went wrong you are back in the plain repl
 * `[:unrepl/hello ...]` success!
 * anything else should be ignored.