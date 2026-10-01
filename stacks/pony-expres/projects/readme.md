# Pony Expres comparison projects

Pony Expres uses two dedicated Pony-native fixtures so transport/lifecycle parity is exercised across distinct application handlers.

| Fixture org | Source → target | Canary behavior | Current proof |
| --- | --- | --- | --- |
| `ores-dummy-org-pony-native-1` | Pony → native | framed echo actor | compile/frame smoke contract defined |
| `ores-dummy-org-pony-native-2` | Pony → native | structured runtime-metadata actor | compile/frame smoke contract defined |

Both canaries preserve the platform's actual guest model: one warm OS process, U32 big-endian length-prefixed stdin/stdout framing, and a fresh Pony `Invocation` actor per request. Their manifests use the existing `poex capabilities` / `poex invoke` runtime surface.

Pony Expres remains `status: registered` until both native binaries pass their framing smoke tests and real `poex invoke` receipts are recorded. The two orgs intentionally share the protocol while differing in handler behavior.
