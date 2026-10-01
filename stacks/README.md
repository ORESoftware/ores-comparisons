# Comparison stack registry

`shared/stack-catalog.json` is the machine-readable registry for comparison runtimes and the governed FaaS coverage cohort.

Four independent concepts are intentionally kept separate:

- **FaaS coverage** means the platform is a first-class comparison identity in the catalog and has a stable backing stack.
- **Topology materialization** means the required dummy organizations and repository families exist and are governed by the comparison fleet.
- **Artifact/admission evidence** means a source→target lane has a deterministic build/admission contract and pinned canary revision.
- **Executable/materialized coverage** means the stack has actual stack-native runtime proof plus benchmark/runtime-proof integration.

A materialized dummy-org topology or a compile command is not benchmark evidence. CI must never count a repository scaffold, mock compiler output, or unexecuted command as executable coverage.

## Governed FaaS cohort

The repository must recognize at least seven distinct FaaS platforms. The current cohort contains eight:

| FaaS platform | Catalog stack / GitHub identity | Dummy-org topology | Executable status |
| --- | --- | --- | --- |
| Scintilla Run | `scintilla-run` | materialized through the six scenario orgs | materialized |
| Iso Lattes | `iso-lattes` | `gleam-js` + `typescript-js` fixture orgs | registered |
| Lunatic Lorry | `lunatic-lorry` | `rust-wasm` + `zig-wasm` fixture orgs | registered |
| WASM Xprs | `wasm-xprs` | `rust-wasm` + `zig-wasm` fixture orgs | registered |
| LiteGraph | `litegraph` | `rust-gpu` + `cuda-gpu` fixture orgs | registered |
| BeamScale | `beamscale` | materialized through the six scenario orgs | materialized |
| GraalVM | `graal-show` (`graal-vm` platform alias) | `clojure-jvm` + `java-jvm` fixture orgs | registered |
| Pony Expres | `pony-expres` | two `pony-native` fixture orgs | registered |

`graal-vm` is the public/runtime platform name used by the comparison cohort; `graal-show` remains the canonical stack and GitHub organization identity.

ORES Stack remains a materialized comparison/control stack, but it is not used to satisfy the minimum FaaS-platform count. Its dedicated runtime fixtures are `ores-dummy-org-rust-native-1` and `ores-dummy-org-rust-wasm-3`.

## Dummy-org topology

The six application-scenario orgs remain governed by `shared/dummy-org-map.json` and exact gitlinks in `shared/dummy-org-gitlinks.json`.

The 14 source→target fixture orgs are governed by `shared/dummy-org-fleet.json`. Together the two authorities cover **20 dummy organizations**.

Dedicated runtime fixture orgs use:

`ores-dummy-org-<source>-<target>-<n>`

The source/target pair is fail-closed. In particular, TypeScript → WASM is not an admitted lane.

Every source→target fixture has one `web-server.<language>` canary revision tracked by `shared/runtime-execution-evidence.json`. The canary is the first executable boundary for that org; the rest of the 19-repository family may consume the same contract as it is implemented.

## Catalog states

| Field/state | Meaning |
| --- | --- |
| `topology_status: materialized` | Required dummy-org topology is declared and remotely verifiable. It is not runtime proof. |
| `status: registered` | Stable runtime identity exists, but executable benchmark/runtime evidence is not yet admitted. |
| `status: materialized` | Full governed runtime/build/benchmark evidence is admitted to executable comparison coverage. |

## Topology gate

A dedicated non-BEAM fixture stack must have, at minimum:

1. two runtime fixture orgs;
2. source and artifact/runtime target encoded in each org name;
3. the governed 19-role repository family plus `.github` in each org;
4. source-language extensions on API/web/MCP server repository names;
5. no forbidden source→target pair such as TypeScript → WASM;
6. `shared/dummy-org-fleet.json`, `shared/runtime-fixture-submodules.json`, and `shared/stack-catalog.json` agreeing exactly;
7. a pinned canary revision for each runtime fixture in `shared/runtime-execution-evidence.json`;
8. offline validation through the Rust runtime-execution gate plus the existing topology/gitlink checks;
9. authorized remote branch reachability where private repositories are dereferenced.

## Two executable promotion paths

A stack can reach executable `materialized` status through one of two governed paths.

### Scenario-backed path

BeamScale, Scintilla Run, and the ORES Stack control lane already use the six shared application scenarios. That path requires the governed project matrix, scenario branches/gitlinks, stack-native build/deploy smoke verification, benchmark/runtime-proof integration, and the normal contract/parity gates.

### Dedicated runtime-fixture path

Stacks whose execution model does not share the BEAM scenario implementation may instead use their dedicated source→target fixture orgs. Promotion requires:

1. at least two governed fixture orgs for the stack;
2. every declared fixture represented in `shared/runtime-execution-evidence.json`;
3. deterministic source→artifact build/admission metadata in each canary repo;
4. all required compiler/runtime ABIs represented honestly (for example `wasmx-v1`, Lunatic mailbox framing, JVM admission, Pony framing, or real accelerator targets);
5. `runtime_proven: true` for every declared fixture, backed by actual build/invoke receipts rather than commands that merely exist;
6. real hardware evidence for GPU claims;
7. benchmark or conformance evidence that exercises the same logical contract across the stack's fixture lanes;
8. a non-null benchmark executable/runtime entrypoint in the stack catalog;
9. all stack catalog, benchmark TypeSpec/JSON Schema, and runtime-execution Rust gates passing in the same change set.

Topology, source compilation alone, or mock compiler output does not satisfy this gate. `tools/verify_runtime_execution.rs` fails closed if a fixture-backed stack is marked `materialized` while any declared fixture remains unproven.

The Rust stack-catalog gate continues to fail closed if the FaaS cohort drops below seven distinct platforms, if any required platform disappears, if two platform identities reuse one backing stack, or if the GraalVM platform stops mapping to the `graal-show` fleet identity.
