# Oreslang Stack — independent comparison stack

`oreslang-stack` is a framework for **Oreslang (`.ores`)** application source, not an alias for the existing Rust-based `ores-stack`.

- Framework and CLI repository: https://github.com/ores-truffle-oreslang/oreslang-stack-cli
- Oreslang source fixtures: https://github.com/orgs/ores-dummy-org-oreslang-stack/repositories
- Cross-stack portability ledger: `shared/oreslang-portability.json`
- Provider-neutral Lambda runtime ledger: `shared/oreslang-lambda-runtime.json`
- Detailed architecture and promotion requirements: `docs/ORESLANG-STACK.md`

**Status: registered; topology pending.** The Oreslang-authored CLI and its Java/Graal route/RPC/Lambda source-generation path now exist, but that does not make the stack materialized.

The first provider-neutral Lambda execution layer is separately pinned as a **candidate**: runtime PR #1151, stack CLI PR #17 and Lambda fixture PR #7. It targets a strict-FaaS, deny-all, bounded `String -> String` local invocation ABI. Exact-head source-org Actions failed before executing test steps, so no runtime receipt is admitted yet.

There is still no certified provider deployment adapter, `oreslang-faas` host, benchmark executable, portability Gitlink set, or LLVM Lambda parity. `deploy` must remain fail-closed until those proofs land.
