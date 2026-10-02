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
| Graal Show | `ores-dummy-org-ruby-graal-1` | Ruby (Roda fixture) | Graal/TruffleRuby |
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

Each complete runtime fixture org owns `.github` plus the same 19-role repository family, for 20 repositories total. Runtime-facing server and MCP repository names use the source-language extension (`.clj`, `.java`, `.rb`, `.gleam`, `.ts`, `.rs`, `.zig`, `.pony`, or `.cu`). `desktop-app.rs` remains Rust and `flutter` remains Flutter because those are client-shell roles rather than the server runtime under test.

## Materialization versus executable proof

`topology_status: materialized` means the intended dummy-org topology is declared. It does **not** mean the stack has passed executable benchmark/runtime proof, and it does not override the fail-closed repository/submodule state in `shared/runtime-fixture-submodules.json`.

The pre-existing `status` field in `shared/stack-catalog.json` remains the executable gate:

- `registered`: topology may be declared/materialized, but runtime/build proof is not yet admitted.
- `materialized`: full executable scenario/benchmark evidence exists.

This distinction prevents repository creation or submodule wiring from being counted as runtime evidence.

## Private fixture transport

Private repositories are valid Git submodules. Runtime fixture repos are pinned as ordinary `160000` gitlinks under:

```text
stacks/<stack>/projects/<source-target-index>/repos/<repo>
```

`.gitmodules` points at the private HTTPS GitHub URL and tracks `main`; the superproject gitlink pins the exact commit. A developer or CI job materializing those submodules must independently authenticate to the private repositories.

`shared/runtime-fixture-submodules.json` is the fail-closed materialization ledger. It distinguishes:

- `complete_orgs`: `.github` plus all 19 role repos are pinned;
- `partial_orgs`: only the explicitly listed existing repositories are pinned;
- `pending_orgs`: no gitlinks are admitted yet because the expected repositories do not exist.

At the time this transport was introduced, seven fixture orgs were complete, `ores-dummy-org-zig-wasm-2` had 10 of its expected 20 repositories, and the six later fixture orgs had no repositories. Those incomplete creation states are intentionally recorded rather than represented by broken submodule URLs.

## Verification

Offline, fail-closed topology and gitlink validation:

```sh
python3 scripts/verify_runtime_fixture_org_map.py
python3 scripts/render_runtime_fixture_gitmodules.py --check
python3 scripts/verify_runtime_fixture_gitlinks.py
```

Authorized remote reachability validation:

```sh
python3 scripts/verify_runtime_fixture_remote_reachability.py
```

When a cross-repository read credential is present, CI verifies every currently materialized private fixture repository's `main` branch. Without that credential, the exact local gitlink and `.gitmodules` projections are still mandatory.


## Graal Show Ruby framework references

Ruby is intentionally represented in two ways:

- `ores-dummy-org-ruby-graal-1` is the governed dummy-org lane and uses Roda in normal server mode, proving that Graal lambda generation is not Rails-specific. The comparison pins the complete 20-repository family; the API server contains the executable Roda → framework-free Graal lowering fixture.
- `ores-ror-to-lambdas-demo/ores-ror.rb` plus `ores-ror-to-lambdas-demo/ores-ror.infra` are exact Rails reference gitlinks governed by `shared/graal-ruby-references.json`. They demonstrate the same app-native lowering contract with Rails as the authoring framework.

In both cases the Graal worker artifact is expected to be framework-free Ruby; framework parsing/boot happens before admission, not inside the guest worker.
