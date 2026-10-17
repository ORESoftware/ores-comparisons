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

The remaining repositories, `ores-dummy-org-oreslang-stack-infra` and `ores-dummy-org-oreslang-stack-lambdas`, are separate **non-deployable candidate** roles, not interchangeable copies of the two app source fixtures. The Lambda repository now also serves as the independently governed provider-neutral runtime candidate, but it still must not inflate the two-source portability count. Its execution state is governed by `shared/oreslang-lambda-runtime.json`, separately from this portability corpus. Immutable Git submodule SHAs and full execution evidence remain pending. Never synthesize submodules or credentials.

These two repositories form a *cross-stack portability corpus*, not another governed 20-repo source/runtime fixture organization. This leaves the historical six scenario organizations and 15 dedicated runtime-fixture organizations unaffected.

`shared/oreslang-portability.json` lists **every registered comparison stack**. There are currently **10**, including both `ores-stack` and `oreslang-stack`; their `status` is `pending` with no pins until the exact source SHAs, authenticated reachability, and materialized Gitlinks are independently verified.

The intended location of each pinned source repo is:

```text
stacks/<target-stack>/projects/oreslang-portability/repos/<existing-repository-name>
```

Pin the actual Git remote `https://github.com/ores-dummy-org-oreslang-stack/<existing-repository-name>.git`, its verified default branch, and an **immutable** commit SHA via a true Git mode-`160000` submodule, not a copied directory. Record the exact repository name, branch, pin, and path in the ledger. The complete topology for **10 stacks × 2 repos = 20 Gitlinks** must still pass remote read authentication and does not itself count as executable success.

`python3 scripts/verify_oreslang_portability.py` checks that the targets exactly match the catalog and rejects undeclared, stale or mismatched Gitlinks, SHA, URL, branch, and false completion. `just verify` and `just verify-static` both execute the gate. Reachability needs separate authenticated private-repo CI.

## Framework CLI and proof acceptance

The `ores-truffle-oreslang/oreslang-stack-cli` is now a real Oreslang-authored CLI: its command dispatcher, route discovery, RPC/Lambda/GraphQL source generation and admission logic live in `src/main.ores`. Current main supports `init`, `check`, `routes`, `generate`, `dev`, and source-bundle `build` on the Java/Graal runtime. `deploy` deliberately fails closed, and LLVM cannot execute this CLI yet.

The next runtime layer is represented by three draft, immutable-head-bound implementation candidates: Java/Graal runtime PR **#1151**, Oreslang Stack CLI PR **#17**, and Lambda fixture PR **#7**. They introduce a provider-neutral local `String -> String` invocation path without claiming provider deployment. Their exact candidate heads are governed in `shared/oreslang-lambda-runtime.json`. Source-repository Actions on #1151 and #17 failed before executing any job steps, so this repository records the implementation as **candidate**, not verified execution.

1. **Genuine language compilation:** Parse and type-check real `.ores` sources; explicitly report unsupported grammar or backend semantics.
2. **Contract/codegen consistency:** Derive GraphQL, RPC and Lambda interfaces from actual Oreslang application domain contracts and TypeSpec/JSON Schema/Protobuf authorities, with deterministic outputs, negative tests and serialization conformance. Never rename Rust artifacts and call them Oreslang.
3. **Provider-neutral Lambda execution:** Invoke an explicit public `String -> String` export under the Java/Graal strict-FaaS isolate with deny-all scoped host I/O, bounded UTF-8 event/response payloads and logs separated from the response channel. Local execution is distinct from provider deployment.
4. **Independent compiler paths:** Collect distinct Java/Graal and LLVM compiler inputs, artifact provenance, smoke results, and clear incompatibility diagnostics.
5. **Actor/OTP behavior:** Test isolated actors, supervisor restart/lifecycle rules, message handling and compatible hot-loading. Do not claim OTP parity while supervisor hot-loading is unproven.
6. **Runtime and host separation:** A stack/framework build or local Lambda invoke does not imply FaaS support. Self-hosted runtime and prospective `oreslang-faas` must each prove explicit startup, readiness, teardown and an admitted deployment adapter.
7. **Portability evidence:** For every comparison target stack, record one of *built and executed*, *explicitly unsupported*, or *pending* against the pinned fixture and exact runtime/toolchain versions. A Gitlink alone does not mean the target natively understands `.ores`.

### Provider-neutral Lambda candidate

The v1 candidate is intentionally small:

```text
stdin UTF-8 event
      |
      v
oreslang-stack invoke
      |
      v
Java/Graal strict-FaaS isolate
      |
      v
public handler.invoke(String) -> String
      |
      v
stdout response
```

The host caps event and response payloads at **1 MiB**, gives the guest no scoped host-I/O permissions, and keeps guest logs out of the response channel. The concrete fixture remains `deployable = false` with no provider assigned. Promotion from `candidate` requires an exact-head execution receipt; AWS Lambda bootstrap/OCI packaging, `oreslang-faas`, warm-pool reuse, async handler ABI, LLVM parity and deployment receipts remain separate work.

## Staged admission

- **Registration (this PR):** Oreslang Stack catalog identity, peer benchmark schema/type, scaffold, cross-stack manifest and offline integrity gate.
- **Private fixture admission:** Resolve the already-inventoried two source repositories to immutable reviewed commits; add **20** pinned Gitlinks across the 10 current stack targets; verify authenticated reachability.
- **Executable implementation:** The Oreslang-authored CLI and source generators now exist; provider-neutral Java/Graal Lambda invocation is a separately gated candidate. Exact-head execution receipts, backend builds, actors/supervisors and deployment adapters remain required.
- **Benchmark/FaaS promotion:** Oreslang-authored six-scenario parity, verified runtime smoke runs, benchmark matrix and domain contracts. Only then promote the stack to `materialized` and consider registering Oreslang FaaS as an admitted platform.

## Backend artifact × execution host matrix

Oreslang Stack now governs backend choice separately from execution host choice.
The backend selects the executable artifact; the host selects where and how that
artifact runs. The machine authority is
`shared/oreslang-backend-host-matrix.json`.

| Backend | Artifact | Host(s) | Isolation |
| --- | --- | --- | --- |
| Graal | Graal runtime / Native Image | `graal-show` | Graal polyglot/native isolate |
| LLVM native | native executable/library | `scintilla-run`, `litegraph` | worker process / specialized host process |
| LLVM → WASM | `wasm32-wasi` | `wasm-xprs`, `lunatic-lorry` | WASM instance / actor-process |
| direct WASM | `wasm32-wasi` | `wasm-xprs`, `lunatic-lorry` | WASM instance / actor-process |
| .NET NativeAOT | standalone native executable | `scintilla-run` | worker process; untrusted additionally requires OS sandboxing |
| JavaScript | ESM | `iso-lattes` | JavaScript isolate |

The .NET mapping is intentionally **NativeAOT → worker process**. A collectible
`AssemblyLoadContext` is useful for development-time assembly versioning and
unloadability, but it is not admitted as a security boundary and is incompatible
with the strict NativeAOT release path. Oreslang .NET PR `#81` now also carries
the candidate **ORW1** worker protocol: bounded binary stdio frames, strict
UTF-8, 1 MiB payload ceiling, 30 s maximum deadline, strictly increasing request
IDs, opaque-error-and-retire on unknown guest failure, and a statically linked
`oreslang-app --worker` entrypoint. The current .NET guest ABI remains
`main() -> i64`; payload Lambda ABI is explicitly false.

Scintilla is still a **planned** host for this artifact. Its current reusable
child protocol is line-oriented JSON, so the matrix requires
`pending-orw1-adapter` and must reject any claim that the .NET route is
executable before an explicit ORW1 adapter/port driver runs successfully.

WASM likewise requires a genuine WASM artifact. A .NET or native host embedding a
WASM engine does not make arbitrary CIL/native code a WASM-sandboxed workload.

All rows remain planned/future-backend. This table is an admission map, not a
deployment or benchmark-success claim.

## Explicit permitted deployment matrix (not an executable claim)

The two `.ores` repositories may remain **source submodules in all ten comparison stack directories** for visibility and negative portability tests. That does **not** mean Oreslang is deployable to all ten stacks. The governed `deployment_matrix` in `shared/oreslang-portability.json` permits only these seven prospective targets:

| Stack | Eligibility | Required runtime/compiler path |
| --- | --- | --- |
| `oreslang-stack` | planned | Native Oreslang framework/runtime |
| `scintilla-run` | planned | Explicit Oreslang polyglot runtime adapter |
| `wasm-xprs` | future-backend | Oreslang → WASM (not yet implemented/proven) |
| `iso-lattes` | future-backend | Oreslang → JavaScript or WASM (not yet implemented/proven) |
| `graal-show` | planned | Graal runtime / Native Image host adapter with exact execution receipt required |
| `lunatic-lorry` | future-backend | Oreslang → WASM (not yet implemented/proven) |
| `litegraph` | planned | LLVM-compatible LiteGraph runtime/GPU adapter, target ABI and execution proof required |

`beamscale` is **unsupported** because Oreslang will not target Erlang/BEAM; `pony-expres` is **unsupported** because Oreslang does not target Pony; `ores-stack` (Rust) remains unsupported as an Oreslang deployment target. `graal-show` is now a **planned** Graal host, not an executable claim. Neither `planned` nor `future-backend` means deployable today. The CLI must reject unsupported targets and must fail closed for every unimplemented adapter, including all seven prospective routes above.
