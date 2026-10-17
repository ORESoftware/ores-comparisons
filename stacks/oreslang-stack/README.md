# Oreslang Stack — independent comparison stack

`oreslang-stack` is a framework for **Oreslang (`.ores`)** application source, not an alias for the existing Rust-based `ores-stack`.

- Framework and CLI repository: https://github.com/ores-truffle-oreslang/oreslang-stack-cli
- Oreslang source fixtures: https://github.com/orgs/ores-dummy-org-oreslang-stack/repositories
- Cross-stack portability ledger: `shared/oreslang-portability.json`
- Provider-neutral Lambda runtime ledger: `shared/oreslang-lambda-runtime.json`
- Backend/host isolation matrix: `shared/oreslang-backend-host-matrix.json`
- Detailed architecture and promotion requirements: `docs/ORESLANG-STACK.md`

**Status: registered; topology pending.** The Oreslang-authored CLI and its Java/Graal route/RPC/Lambda source-generation path now exist, but that does not make the stack materialized.

The first provider-neutral Lambda execution layer is separately pinned as a **candidate**: runtime PR #1151, stack CLI PR #17 and Lambda fixture PR #7. It targets a strict-FaaS, deny-all, bounded `String -> String` local invocation ABI. Exact-head source-org Actions failed before executing test steps, so no runtime receipt is admitted yet.

There is still no certified provider deployment adapter, `oreslang-faas` host, benchmark executable, portability Gitlink set, or LLVM Lambda parity. `deploy` must remain fail-closed until those proofs land.

## Planned backend/host routes

- Graal -> `graal-show`
- LLVM/native -> `scintilla-run` and specialized `litegraph`
- LLVM/WASM or direct WASM -> `wasm-xprs` / `lunatic-lorry`
- .NET NativeAOT -> `scintilla-run` worker process; ORW1 codec/port-worker candidate is pinned from Scintilla PR #55 but remains disconnected from live runtime admission, and untrusted workers additionally require OS sandboxing
- JavaScript -> `iso-lattes`

These are governed prospective routes only. AssemblyLoadContext is not a .NET
security boundary, and no row is promoted to executable/deployable merely by
appearing here.

### .NET worker candidate

The governed .NET head now includes a statically linked `oreslang-app --worker`
mode using the ORW1 v1 framed stdio protocol. This is **not** Lambda ABI parity:
the current guest entry remains `main() -> i64`, invocation payloads must be
empty, and non-empty payloads fail closed. Unknown guest failures retire the
worker; request IDs are strictly increasing per worker generation. Scintilla
integration remains planned until its line-oriented child runtime has an
explicit ORW1 adapter and executed receipt.
