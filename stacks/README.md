# Comparison stack registry

`shared/stack-catalog.json` is the machine-readable registry for comparison runtimes and the governed FaaS coverage cohort.

Three independent concepts are intentionally kept separate:

- **Registered coverage** means the platform has a stable identity, comparison branch name, execution-model description, and scaffold.
- **Materialized coverage** means all six governed scenario envelopes, dummy-org branch names, `.gitmodules` entries, and immutable gitlinks exist.
- **Verified coverage** means materialization is backed by stack-native build/deploy smoke evidence and runtime-proof integration.

A materialized stack is not benchmark evidence until it is verified.

## Governed FaaS cohort

The repository must recognize at least seven distinct FaaS platforms. The current cohort contains eight:

| FaaS platform | Catalog stack / GitHub identity | Catalog status | Runtime verified? |
| --- | --- | --- | --- |
| Scintilla Run | `scintilla-run` | verified | yes |
| Iso Lattes | `iso-lattes` | materialized | no |
| Lunatic Lorry | `lunatic-lorry` | materialized | no |
| WASM Xprs | `wasm-xprs` | materialized | no |
| LiteGraph | `litegraph` | materialized | no |
| BeamScale | `beamscale` | verified | yes |
| GraalVM | `graal-show` (`graal-vm` platform alias) | materialized | no |
| Pony Expres | `pony-expres` | materialized | no |

`graal-vm` is the public/runtime platform name used by the comparison cohort; `graal-show` remains the canonical stack and GitHub organization identity.

ORES Stack remains a verified comparison/control stack, but it is not used to satisfy the minimum FaaS-platform count.

## Catalog states

| State | Meaning |
| --- | --- |
| `registered` | Stable identity and scaffold only. No topology or executable coverage is claimed. |
| `materialized` | All six governed project envelopes and immutable dummy-org gitlinks exist. No benchmark/runtime proof is claimed. |
| `verified` | Materialized plus stack-native build/deploy smoke checks and runtime-proof/benchmark admission. |

`shared/materialization-matrix.json` owns the 9 x 6 topology. `shared/project-matrix.json` remains the fail-closed executable/runtime authority and therefore contains only verified stacks.

The original runtime-verified gitlinks remain in `shared/dummy-org-gitlinks.json`. Topology-only gitlinks for the newly materialized stacks are isolated in `shared/materialized-dummy-org-gitlinks.json`; the structural verifier requires the union of both ledgers to match the Git index and `.gitmodules` exactly.

## Materialization gate

Moving a stack from `registered` to `materialized` requires, in one change set:

1. all six comparison scenarios under `stacks/<stack>/projects/`;
2. matching `stack/<stack>` branches in every governed dummy-org repository;
3. `.gitmodules` entries plus immutable materialization-ledger evidence;
4. `shared/materialization-matrix.json` and `shared/dummy-org-map.json` updated together;
5. `scripts/verify_dummy_org_gitlinks.py`, project layout checks, and the Rust stack-catalog gate passing.

## Verification gate

Moving a stack from `materialized` to `verified` additionally requires:

1. stack-native build/deploy metadata and smoke verification;
2. admission to `shared/project-matrix.json` and, where applicable, `benchmarks/matrix.json`;
3. a real `benchmark_executable`;
4. runtime/build evidence and receipt integration;
5. no substitution of a generic runner for the stack-native CLI/runtime.

The Rust gate also fails closed if the FaaS cohort drops below seven distinct platforms, if any required platform disappears, if two platform identities reuse one backing stack, or if the GraalVM platform stops mapping to the `graal-show` fleet identity.
