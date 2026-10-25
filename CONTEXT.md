# client (bonsapi)

The Java client library that applications use to talk to a Bonsai cluster. Owns the wire-level `Connection` adapters (TCP, HTTP), the typed `RemoteTable`/`RemoteRoot` façade, and the client-side cache that mirrors server state via server-pushed events.

## Language

**ChangeEvent**:
A typed record carrying a single key-level update from the server — `(db, table, key, value, isDelete, ttlExpiry, globalSeq, timestamp)`. Defined in the shared `bonsai/` protocol module and reused on both server-downstream and client-receive sides.
_Avoid_: "invalidation message" (misleading — `ChangeEvent` carries the new value on writes, not just a bust), "push frame", "delta".

**ChangeEventRouter**:
The client-side module that receives a `ChangeEvent` from whichever transport produced it and routes it to the right `RemoteTable` for cache application. Owns a `Map<(db,table), RemoteTable>` populated by `bind(...)`. The transport calls `router.accept(event)`; the router does the lookup and hands the event to the bound table.
_Avoid_: "InvalidationStream" (we deliver puts as well as invalidates — that name was the old, value-dropping shape), "cache bus" (too generic), "CacheCoherence" (describes the property, not the module).

**Push-capable source**:
A `Connection` (or future push-only side-channel) that can deliver server-initiated `ChangeEvent`s to the client. A push-capable source registers itself with the `ChangeEventRouter` at startup; non-push transports (HTTP) do not register, and the router's `hasSource()` returns `false` for them.
_Avoid_: "subscriber-supporting connection", `supportsInvalidation()` (the old boolean we replaced with registration).

**Push-fed cache**:
A `RemoteTable`'s Caffeine cache that stays coherent with the server only because a `ChangeEventRouter` is delivering updates. Enabled only when `router.hasSource()` is true and the table is non-local (i.e., shared with the server). HTTP-backed `RemoteRoot`s do not enable push-fed caches today; volatile local-only caches are unrelated and unaffected.
_Avoid_: "remote cache" (ambiguous — could mean a server-side cache), "tracked cache".

**Bind / unbind**:
The client-side lifecycle for a `RemoteTable`'s subscription to the `ChangeEventRouter`. `router.bind(db, table, RemoteTable)` registers the table to receive events; `unbind(db, table)` removes it (called when the table is closed or its parent root shuts down). Separate from the *wire-level* subscription to the server (`subscribeToInvalidations`), which the `Connection` still owns.

## Relationships

- A **RemoteRoot** wires exactly one **Connection** (`TcpConnection` or `HttpConnection`) and exactly one **ChangeEventRouter**. Both have the lifetime of the root.
- A **push-capable source** registers itself with the router at construction. A non-push source does not.
- A **RemoteTable** that is push-fed must `bind` to the router on creation and `unbind` on close.
- A **ChangeEvent** flows: server → transport decode → `router.accept` → bound `RemoteTable.applyChangeEvent` → typed deserialize → `cache.put` (write) or `cache.invalidate` (delete).
- The **per-key ordering** invariant is inherited from the source thread: the router dispatches synchronously, so events from one source thread are routed in arrival order. (Per-key worker dispatch is a future optimisation if profiling shows the source thread blocking.)

## Example dialogue

> **Dev:** "If a client app uses HTTP, what happens to its `RemoteTable` cache?"
> **Maintainer:** "HTTP isn't push-capable — `HttpConnection` doesn't register with the router. `router.hasSource()` returns false, so `RemoteTable` skips its push-fed Caffeine cache. Local-only volatile caches (TTL-bounded, no coherence claim) are unrelated and still allowed."
>
> **Dev:** "What if I write a gRPC push transport later?"
> **Maintainer:** "Register it with the router the same way `TcpConnection` does. Decode `ChangeEvent`s on your reader thread, call `router.accept(event)`. Nothing else has to change — `RemoteTable` doesn't know which transport sent the event."

## Flagged ambiguities

- "Invalidation" was historically used to mean both *the wire-level INVALIDATE op* and *the act of busting a cache entry*. The first is a transport detail; the second is a `RemoteTable` outcome that the router triggers when `ChangeEvent.isDelete == true`. They are distinct.
- `Connection.supportsInvalidation()` (boolean) was used as a proxy for "does this transport push changes?". Replaced by source registration with the router. The boolean's call sites collapse into one question on the router itself.
