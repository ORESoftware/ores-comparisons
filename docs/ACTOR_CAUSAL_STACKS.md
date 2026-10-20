# Actor causal chains in error diagnostics

Oreslang does not use the physical carrier thread stack as a cross-actor
stack: message sends, actor scheduling, and `await` sever that Java stack.
Instead, a logical trace is copied when a source-level actor invocation is
spawned, and an `ACTOR_MESSAGE` boundary records the receiving actor.

## Retention contract

For source-level `spawn actor_fn(...)` invocation chains sharing causal
context, an error retains the **16 most recent visited actors**, including
the **last five** for chains at least five actors deep. This is a separate
bounded history, so arbitrarily many await/select/call events cannot evict
the actor chain. Earlier actor hops have an explicit `elided_actor_visits`
count. The latest actor appears first in text and the JSON
`actor_history` array is oldest-to-newest.

The actor-history list is a *causal breadcrumb trail*, **not** a physical
stack of concurrently executing actors. Actors may no longer be running
when a descendant fails. Fan-out creates separate child trace snapshots;
it must not accidentally mix unrelated siblings' visited actors.

Each hop carries only the actor's function symbol and source site,
not message arguments, message bodies, private state, capabilities,
credentials, or physical Java thread identities. Existing source
sanitization applies in text and JSON.

## Machine-readable format

`--json` stderr diagnostics preserve schema `oreslang.error.v1`. Each
rendered logical trace can contain:

```json
{
  "actor_history_order": "oldest_to_newest",
  "actor_history": [
    {
      "actor": {
        "symbol": "Actor5",
        "site": {
          "source": "workers.ores",
          "line": 24,
          "column": 1,
          "generation": null
        }
      },
      "message_site": {
        "source": "workers.ores",
        "line": 24,
        "column": 1,
        "generation": null
      }
    }
  ],
  "elided_actor_visits": 0
}
```

The above is an excerpt of a trace, not a complete diagnostic envelope.
Older `events` can be elided independently.

## Validation and scope

- Runtime tests exercise 10 actor hops with 1,500 await events and a
  200-hop chain that retains the final 16 actors.
- An executable eight-actor failure fixture is checked via CLI text and JSON.
- These tests are *requested*, not evidence of passing CI until run on the
  exact PR Git tree.

This change specifically records source-level actor spawn/message boundaries
that already flow through `OresAsyncTrace`. Host-level `ActorRuntime.ActorRef.send`,
remote `ProxyActor` transports, and external IPC/RPC envelopes require separate
explicit propagation, isolation, and trust-boundary integration before the
same five-hop guarantee can be claimed for those paths.
