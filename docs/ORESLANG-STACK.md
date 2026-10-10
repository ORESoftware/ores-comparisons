# Oreslang Stack: framework, hosting and cross-stack conformance

## Four separate product identities

| Identity | Responsibility |
| --- | --- |
| `ores-stack` | Existing **Rust** framework, source generators, CLI and native/WASM app builds |
| `oreslang` | **Oreslang** source language, actors/supervisors and separate JVM/Graal and LLVM compiler paths |
| `oreslang-stack` | Framework + CLI for `.ores` apps: HTTP routes, GraphQL/RPC/Lambda interfaces, contract-driven artifact builds and explicit deployment adapters |
| `oreslang-faas` | Proposed independently hosted FaaS service; not equivalent to the framework, and not admitted as executable yet |

The intended analogy is Next.js (framework) versus Vercel (host). An Oreslang application may be authored against Oreslang Stack and deployed through a verified self-hosted runtime or a future Oreslang FaaS adapter. A successful LLVM build does not prove Graal works; generating artifacts does not prove deployment works.

## Existing source fixtures reused under every stack

The GitHub organization `ores-dummy-org-oreslang-stack` has **four verified repositories** on October 9, 2026. Exactly **two are Oreslang portability application sources**:

- `ores-dummy-org-oreslang-stack-api-server.ores` (`main`)
- `ores-dummy-org-oreslang-stack-web-server.ores` (`main`)

The remaining repositories, `ores-dummy-org-oreslang-stack-infra` and `ores-dummy-org-oreslang-stack-lambdas`, are separate **non-deployable candidate** roles, not interchangeable copies of the two app source fixtures. Their presence must not inflate the two-source portability count or grant executable/runtime status. The manifest records verified identities and branches; immutable Git submodule SHAs and full execution evidence are still pending. Never synthesize submodules or credentials.

These two repositories form a *cross-stack portability corpus*, not another governed 20-repo source/runtime fixture organization. This leaves the historical six scenario organizations and 15 dedicated runtime-fixture organizations unaffected.

`shared/oreslang-portability.json` lists **every registered comparison stack**. There are currently **10**, including both `ores-stack` and `oreslang-stack`; their `status` is `pending` with no pins until the exact source SHAs, authenticated reachability, and materialized Gitlinks are independently verified.

The intended location of each pinned source repo is:

```text
stacks/<target-stack>/projects/oreslang-portability/repos/<existing-repository-name>
```

Pin the actual Git remote `https://github.com/ores-dummy-org-oreslang-stack/<existing-repository-name>.git`, its verified default branch, and an **immutable** commit SHA via a true Git mode-`160000` submodule, not a copied directory. Record the exact repository name, branch, pin, and path in the ledger. The complete topology for **10 stacks × 2 repos = 20 Gitlinks** must still pass remote read authentication and does not itself count as executable success.

`python3 scripts/verify_oreslang_portability.py` checks that the targets exactly match the catalog and rejects undeclared, stale or mismatched Gitlinks, SHA, URL, branch, and false completion. `just verify` and `just verify-static` both execute the gate. Reachability needs separate authenticated private-repo CI.

## Framework CLI and proof acceptance

The proposed `ores-truffle-oreslang/oreslang-stack-cli` command contract includes `init`, `check`, `routes`, `generate`, `build --backend graal|llvm`, `dev`, `plan --target`, and `deploy --target`. The CLI was initialized with **documentation only**, not a runnable executable.

1. **Genuine language compilation:** Parse and type-check real `.ores` sources; explicitly report unsupported grammar or backend semantics.
2. **Contract/codegen consistency:** Derive GraphQL, RPC and Lambda interfaces from actual Oreslang application domain contracts and TypeSpec/JSON Schema/Protobuf authorities, with deterministic outputs, negative tests and serialization conformance. Never rename Rust artifacts and call them Oreslang.
3. **Independent compiler paths:** Collect distinct Java/Graal and LLVM compiler inputs, artifact provenance, smoke results, and clear incompatibility diagnostics.
4. **Actor/OTP behavior:** Test isolated actors, supervisor restart/lifecycle rules, message handling and compatible hot-loading. Do not claim OTP parity while supervisor hot-loading is unproven.
5. **Runtime and host separation:** A stack/framework build does not imply FaaS support. Self-hosted runtime and prospective `oreslang-faas` must each prove explicit startup, readiness, teardown and an admitted deployment adapter.
6. **Portability evidence:** For every comparison target stack, record one of *built and executed*, *explicitly unsupported*, or *pending* against the pinned fixture and exact runtime/toolchain versions. A Gitlink alone does not mean the target natively understands `.ores`.

## Staged admission

- **Registration (this PR):** Oreslang Stack catalog identity, peer benchmark schema/type, scaffold, cross-stack manifest and offline integrity gate.
- **Private fixture admission:** Resolve the already-inventoried two source repositories to immutable reviewed commits; add **20** pinned Gitlinks across the 10 current stack targets; verify authenticated reachability.
- **Executable implementation:** Real Oreslang CLI, domain generators, backend builds, actors/supervisors and deployment adapters with CI receipts.
- **Benchmark/FaaS promotion:** Oreslang-authored six-scenario parity, verified runtime smoke runs, benchmark matrix and domain contracts. Only then promote the stack to `materialized` and consider registering Oreslang FaaS as an admitted platform.

## Explicit permitted deployment matrix (not an executable claim)

The two `.ores` repositories may remain **source submodules in all ten comparison stack directories** for visibility and negative portability tests. That does **not** mean Oreslang is deployable to all ten stacks. The governed `deployment_matrix` in `shared/oreslang-portability.json` permits only these six prospective targets:

| Stack | Eligibility | Required runtime/compiler path |
| --- | --- | --- |
| `oreslang-stack` | planned | Native Oreslang framework/runtime |
| `scintilla-run` | planned | Explicit Oreslang polyglot runtime adapter |
| `wasm-xprs` | future-backend | Oreslang → WASM (not yet implemented/proven) |
| `iso-lattes` | future-backend | Oreslang → JavaScript or WASM (not yet implemented/proven) |
| `lunatic-lorry` | future-backend | Oreslang → WASM (not yet implemented/proven) |
| `litegraph` | planned | LLVM-compatible LiteGraph runtime/GPU adapter, target ABI and execution proof required |

`beamscale` is **unsupported** because Oreslang will not target Erlang/BEAM; `pony-expres` is **unsupported** because Oreslang does not target Pony. `ores-stack` (Rust) and `graal-show` are also unsupported *as deployment targets* absent a separately admitted runtime adapter; any source snapshot under them is for comparisons only. Neither `planned` nor `future-backend` means deployable today. The CLI must reject deployment to unsupported targets and must fail closed for all unimplemented adapters, including these six.
