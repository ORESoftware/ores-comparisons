# Comparison stacks

`shared/stack-catalog.json` is the registry for runtimes admitted into this repository.

A stack can be in one of two states:

- `registered` — the runtime has a stable comparison identity and scaffold here, but it is not yet part of the executable project matrix.
- `materialized` — all governed scenario projects, dummy-org branches/gitlinks, stack-native sources, and verification metadata exist and the stack is eligible for benchmark/runtime-proof coverage.

| Stack | Status | Execution model |
| --- | --- | --- |
| BeamScale (`beamscale`) | materialized | admitted Gleam -> Erlang/BEAM actor/lambda execution |
| Scintilla Run (`scintilla-run`) | materialized | polyglot lambda/container/sub-process runtime |
| ORES Stack (`ores-stack`) | materialized | Rust native servers, WASM/page assets, RPC/API generation |
| Pony Expres (`pony-expres`) | registered | Pony-native actor-oriented lambda/runtime target |
| WASM Xprs (`wasm-xprs`) | registered | WebAssembly-first lambda/component runtime |
| Iso Lattes (`iso-lattes`) | registered | isolate-oriented JavaScript/runtime execution |
| Graal Show (`graal-show`) | registered | GraalVM polyglot lambda/runtime target |
| Lunatic Lorry (`lunatic-lorry`) | registered | Lunatic WebAssembly process/actor runtime target |
| LiteGraph (`litegraph`) | registered | GPU-oriented lightweight isolate/actor lambda runtime |

## Promotion gate

A registered stack must not be marked `materialized` until all six comparison scenarios exist under `stacks/<stack>/projects/`, the corresponding `stack/<stack>` branches exist in every governed dummy-org repository, `.gitmodules` and the immutable gitlink ledger point at those exact revisions, stack-native build/deploy metadata is validated, and the project/benchmark/runtime-proof matrices have been updated.

This split is intentional: adding a stack identity must not make CI report coverage for projects that have not actually been implemented.
