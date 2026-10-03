# Pinned local tooling

The machine-readable authority is `tools/toolchain.lock.json`. Tooling is
double-bound by package version where available and by exact Git commit.

Key pins:

- `ores-compose`: 0.1.0 / `ac081862e9019f219628c970941c95782cee3635`
  - exact source head from `ORESoftware/ores-compose#212`;
  - funded exact-head evidence proves mixed host + OCI execution, one-shot bootstrap barriers,
    `compose_ready`, clean SIGINT teardown, and no leaked matching OCI containers.
- `typespec-json-schema-validator`: 0.1.1 / `e29a91d7ef74e3b0613ea79e988bec4c467535d2`

The local bootstrap also pins BeamScale, Scintilla and ORES Stack CLI/runtime
revisions. Generated projects must not silently use floating `main` branches.

## Rust governance gates

`tools/verify_stack_catalog.rs` is the canonical, network-free stack-registry and
FaaS-cohort admission gate. CI formats it, compiles and runs its unit tests, then
compiles and executes the verifier directly with the pinned Rust toolchain.

The gate protects all of these invariants together:

- at least seven distinct FaaS platform identities are governed;
- the current eight-platform cohort remains present and uniquely backed by catalog stacks;
- `graal-vm` remains an explicit platform alias backed by the canonical `graal-show` fleet identity;
- only `materialized` stacks can claim benchmark executables;
- the materialized set matches the project matrix and dummy-org map exactly;
- catalog stack identities stay in lockstep with the benchmark TypeSpec and JSON Schema authorities;
- every registered stack keeps a comparison scaffold.

`tools/verify_runtime_topology.rs` is the fail-closed runtime-topology admission gate.
Its unit tests run without private submodules; the executable gate runs only after
exact project gitlinks are initialized. It verifies that runtime-bearing repositories
(`application`, `service`, `worker`, and `frontend`) declared by `org.manifest.json`
are present in `.ores-compose.yaml`, that compose dependency ordering is at least as
strong as the manifest dependency graph, that the umbrella `app` is present, and that
materialized runtime repos match their exact superproject gitlinks. Whole-fleet mode
also requires sibling stacks for the same scenario to expose the same runtime repo set.
Per-project runtime jobs emit an immutable topology receipt before `ores-compose`
check/plan/up is allowed to run.

The former Python stack-catalog validator has been retired so there is one
canonical implementation of these fleet-level invariants.

## ORES Stack CLI cutover

The canonical repository is `https://github.com/ores-stack/ores-stack-cli`. This branch pins an exact commit there; merge is gated on that repository becoming a complete, locked, installable workspace and passing its migration parity checks.
