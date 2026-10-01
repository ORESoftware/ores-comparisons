# Dummy-org fleet and runtime fixtures

`ores-comparisons` governs two independent dummy-org axes.

## 1. Cross-stack application scenarios

The six historical organizations remain one-to-one application scenarios:

| Org | Scenario |
| --- | --- |
| `ores-dummy-org-1` | `big-org-example-collaboration` |
| `ores-dummy-org-2` | `big-org-example-commerce` |
| `ores-dummy-org-3` | `big-org-example-operations` |
| `ores-dummy-org-4` | `cached-rpc` |
| `ores-dummy-org-5` | `forms-chat-workflow` |
| `ores-dummy-org-6` | `http-observability` |

Their stack-specific branches and exact gitlinks remain governed by `shared/dummy-org-map.json` and `shared/dummy-org-gitlinks.json`.

## 2. Source → artifact/runtime fixture organizations

Stacks that do not use BeamScale's Gleam → BEAM execution path receive at least two dedicated fixture organizations. The organization name encodes the source language and artifact/runtime target:

`ores-dummy-org-<source>-<target>-<n>`

| Stack | Fixture org | Source | Target |
| --- | --- | --- | --- |
| Graal Show | `ores-dummy-org-clojure-jvm-1` | Clojure | JVM |
| Graal Show | `ores-dummy-org-java-jvm-1` | Java | JVM |
| Iso Lattes | `ores-dummy-org-gleam-js-1` | Gleam | JavaScript |
| Iso Lattes | `ores-dummy-org-typescript-js-1` | TypeScript | JavaScript |
| Lunatic Lorry | `ores-dummy-org-rust-wasm-1` | Rust | WASM |
| Lunatic Lorry | `ores-dummy-org-zig-wasm-1` | Zig | WASM |
| WASM Xprs | `ores-dummy-org-rust-wasm-2` | Rust | WASM |
| WASM Xprs | `ores-dummy-org-zig-wasm-2` | Zig | WASM |
| Pony Expres | `ores-dummy-org-pony-native-1` | Pony | native |
| Pony Expres | `ores-dummy-org-pony-native-2` | Pony | native |
| ORES Stack | `ores-dummy-org-rust-native-1` | Rust | native |
| ORES Stack | `ores-dummy-org-rust-wasm-3` | Rust | WASM |
| LiteGraph | `ores-dummy-org-rust-gpu-1` | Rust | GPU |
| LiteGraph | `ores-dummy-org-cuda-gpu-1` | CUDA | GPU |

TypeScript → WASM is intentionally forbidden in this matrix. TypeScript belongs in the JavaScript target lane; Rust and Zig are the governed WASM source lanes.

Each runtime fixture org owns `.github` plus the same 19-role repository family, for 20 repositories total. Runtime-facing server and MCP repository names use the source-language extension (`.clj`, `.java`, `.gleam`, `.ts`, `.rs`, `.zig`, `.pony`, or `.cu`). `desktop-app.rs` remains Rust and `flutter` remains Flutter because those are client-shell roles rather than the server runtime under test.

## Topology versus execution evidence

All 14 source→target fixture orgs are now topologically complete. `shared/runtime-fixture-submodules.json` has no partial or pending orgs; every fixture org is pinned as private Git submodules.

Topology still does **not** mean executable runtime proof. Three separate authorities are used:

- `shared/dummy-org-fleet.json` — which source→target orgs and repository naming rules are allowed;
- `shared/runtime-fixture-submodules.json` — whether the full private repository topology is pinned;
- `shared/runtime-execution-evidence.json` — which canary revision is under test, its current proof level, whether runtime proof exists, and the explicit blocker when it does not.

Every fixture org uses its language-specific `web-server.<ext>` repository as the first executable canary. This is intentionally narrower than claiming all 19 role repositories are implemented; the remaining family members can adopt the same runtime contract incrementally.

The pre-existing `status` field in `shared/stack-catalog.json` remains the executable gate:

- `registered`: topology may be fully materialized and source/artifact work may exist, but runtime/build proof is not yet admitted;
- `materialized`: full executable runtime/benchmark evidence is admitted.

`tools/verify_runtime_execution.rs` fails closed if fleet/evidence coverage drifts, if TypeScript→WASM appears, if pinned canary revisions are malformed, or if a fixture-backed stack is promoted while any declared runtime fixture remains unproven. GPU runtime proof additionally requires a hardware receipt.

## Current canary proof boundaries

- **Graal Show:** Clojure/JVM and Java/JVM canaries include `gs-compiler` admission policy/build commands; real admission/runtime receipts are still pending.
- **Iso Lattes:** Gleam→JS and TypeScript→JS canaries have deterministic source→JS builds and use the actual `isl capabilities` / `isl invoke` surface; deployed invoke receipts are pending.
- **Lunatic Lorry:** Rust→`wasm32-wasip1` matches the fresh-Lunatic-actor guest model. Zig currently proves a deterministic WASI artifact only; reviewed mailbox/bincode bindings remain a blocker.
- **WASM Xprs:** Rust and Zig guests both implement the no-WASI `wasmx-v1` four-import ABI and define real `wasmx deploy`/`invoke` probes; execution receipts are pending.
- **Pony Expres:** both Pony-native canaries preserve U32BE framing and fresh actor per invocation; one is echo, one structured metadata. Build/frame/runtime receipts are pending.
- **ORES Stack:** the native canary is Axum/Tokio; the WASM canary uses provider-neutral `lambda.rs` plus the `ores-stack` adapter/build receipt path.
- **LiteGraph:** CUDA has a real `sm_80` cubin build contract; Rust currently defines portable workload semantics only. Real GPU execution requires compatible hardware and LiteGraph host evidence.

## Private fixture transport

Private repositories are valid Git submodules. Runtime fixture repos are pinned as ordinary `160000` gitlinks under:

```text
stacks/<stack>/projects/<source-target-index>/repos/<repo>
```

`.gitmodules` points at the private HTTPS GitHub URL and tracks `main`; the superproject gitlink pins the exact commit. A developer or CI job materializing those submodules must independently authenticate to the private repositories.

`shared/runtime-fixture-submodules.json` is the fail-closed topology ledger. A complete org means `.github` plus all 19 role repositories are pinned. Partial/pending states remain valid schema states for future migrations, but the current fleet has neither.

## Verification

Offline topology and gitlink validation remains:

```sh
python3 scripts/verify_runtime_fixture_org_map.py
python3 scripts/render_runtime_fixture_gitmodules.py --check
python3 scripts/verify_runtime_fixture_gitlinks.py
```

Runtime evidence validation is Rust-only:

```sh
just runtime-execution-check
```

Authorized remote reachability validation remains:

```sh
python3 scripts/verify_runtime_fixture_remote_reachability.py
```

When a cross-repository read credential is present, CI verifies private fixture repository reachability and exact gitlinks. Without that credential, the local topology and execution ledgers remain mandatory and fail closed.
