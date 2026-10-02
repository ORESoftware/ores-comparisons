# Comparison stack registry

`shared/stack-catalog.json` is the machine-readable registry for comparison runtimes and the governed FaaS coverage cohort.

Three independent concepts are intentionally kept separate:

- **FaaS coverage** means the platform is a first-class comparison identity in the catalog and has a stable backing stack.
- **Topology materialization** means the required dummy organizations and repository families exist and are governed by the comparison fleet.
- **Executable/materialized coverage** means the stack has admitted stack-native build/deploy verification plus benchmark/runtime-proof integration.

A materialized dummy-org topology is not benchmark evidence. CI must never count a repository scaffold as executable coverage.

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
| GraalVM | `graal-show` (`graal-vm` platform alias) | `clojure-jvm` + `java-jvm` + `ruby-graal` fixture orgs | registered |
| Pony Expres | `pony-expres` | two `pony-native` fixture orgs | registered |

`graal-vm` is the public/runtime platform name used by the comparison cohort; `graal-show` remains the canonical stack and GitHub organization identity.

ORES Stack remains a materialized comparison/control stack, but it is not used to satisfy the minimum FaaS-platform count. Its dedicated runtime fixtures are `ores-dummy-org-rust-native-1` and `ores-dummy-org-rust-wasm-3`.

## Dummy-org topology

The six application-scenario orgs remain governed by `shared/dummy-org-map.json` and exact gitlinks in `shared/dummy-org-gitlinks.json`.

The 15 source→target fixture orgs are governed by `shared/dummy-org-fleet.json`. Together the two authorities cover **21 dummy organizations**.

Dedicated runtime fixture orgs use:

`ores-dummy-org-<source>-<target>-<n>`

The source/target pair is fail-closed. In particular, TypeScript → WASM is not an admitted lane.

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
3. the governed 19-repository family in each org;
4. source-language extensions on API/web/MCP server repository names;
5. no forbidden source→target pair such as TypeScript → WASM;
6. `shared/dummy-org-fleet.json` and `shared/stack-catalog.json` agreeing exactly;
7. offline validation through `scripts/verify_runtime_fixture_org_map.py`;
8. authorized remote branch reachability through `scripts/verify_runtime_fixture_remote_reachability.py`.

## Executable promotion gate

Promoting a stack from `registered` to executable `materialized` remains stricter and requires actual runtime evidence. For the shared six-scenario model that includes the existing project matrix, scenario branches/gitlinks, build/deploy smoke verification, benchmark/runtime-proof integration, and all contract/parity gates. A topology-only fixture is never sufficient by itself.

The Rust stack-catalog gate continues to fail closed if the FaaS cohort drops below seven distinct platforms, if any required platform disappears, if two platform identities reuse one backing stack, or if the GraalVM platform stops mapping to the `graal-show` fleet identity.
