# Comparison stack registry

`shared/stack-catalog.json` is the machine-readable registry for comparison runtimes. It now also contains the governed FaaS coverage cohort.

Two independent concepts are intentionally kept separate:

- **FaaS coverage** means the platform is a first-class comparison identity in the catalog and has a stable backing stack.
- **Executable/materialized coverage** means the stack has all six governed scenarios, matching dummy-org branches/gitlinks, stack-native build/deploy verification, and benchmark/runtime-proof integration.

A registered stack is not benchmark evidence. CI must never count a scaffold as executable coverage.

## Governed FaaS cohort

The repository must recognize at least seven distinct FaaS platforms. The current cohort contains eight:

| FaaS platform | Catalog stack / GitHub identity | Catalog status | Executable today? |
| --- | --- | --- | --- |
| Scintilla Run | `scintilla-run` | materialized | yes |
| Iso Lattes | `iso-lattes` | registered | no |
| Lunatic Lorry | `lunatic-lorry` | registered | no |
| WASM Xprs | `wasm-xprs` | registered | no |
| LiteGraph | `litegraph` | registered | no |
| BeamScale | `beamscale` | materialized | yes |
| GraalVM | `graal-show` (`graal-vm` platform alias) | registered | no |
| Pony Expres | `pony-expres` | registered | no |

`graal-vm` is the public/runtime platform name used by the comparison cohort; `graal-show` remains the canonical stack and GitHub organization identity.

ORES Stack remains a materialized comparison/control stack, but it is not used to satisfy the minimum FaaS-platform count.

## Catalog states

| State | Meaning |
| --- | --- |
| `registered` | Stable identity, comparison branch, execution-model description, and scaffold exist. It is **not** executable matrix coverage. |
| `materialized` | All governed project/runtime evidence exists and the stack is admitted to executable comparison coverage. |

## Promotion gate

Promoting a stack from `registered` to `materialized` requires all of the following in the same change set:

1. all six comparison scenarios under `stacks/<stack>/projects/`;
2. matching `stack/<stack>` branches in every governed dummy-org repository;
3. `.gitmodules` entries and `shared/dummy-org-gitlinks.json` evidence for those branches;
4. stack-native build/deploy metadata and smoke verification;
5. `shared/project-matrix.json`, benchmark/runtime-proof matrices, and `shared/dummy-org-map.json` updated together;
6. the Rust `tools/verify_stack_catalog.rs` gate passing, including benchmark TypeSpec/JSON Schema parity;
7. actual runtime/build evidence before the status changes to `materialized`.

The Rust gate also fails closed if the FaaS cohort drops below seven distinct platforms, if any required platform disappears, if two platform identities reuse one backing stack, or if the GraalVM platform stops mapping to the `graal-show` fleet identity.
