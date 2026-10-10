# Oreslang polyglot client/interface audit — 2026-10-10

## Evidence boundary

This is a dated, **non-certifying** audit. The recursive `main` Git trees of the 36
paired repositories below were inspected on 2026-10-10. None contained paths
named `oreslang` or files ending `.ores`. This is **not** a check of all branches,
all generated-language identifiers, executable builds, private Actions budgets,
or native Oreslang semantic conformance. Do not advertise Oreslang SDK support
from this snapshot. The machine-readable, fail-closed baseline is
[`shared/oreslang-polyglot-source-audit.json`](../shared/oreslang-polyglot-source-audit.json).

An open **draft** below is evidence of a proposed integration, not a published
SDK, contract-parity receipt, release, native/compiler test, or runnable Lambda.

| Organization | Client repository | Interface repository | Open Oreslang draft PRs (as inspected) |
| --- | --- | --- | --- |
| 3FA-app | 3fa-clients | 3fa-interfaces | clients #99, #100; interfaces #51, #52 |
| akrion-sim | akrion-clients | akrion-sim-interfaces | clients #7, #8; interfaces #53 |
| anticaptrad | act-clients | act-interfaces | clients #24 |
| athlet-o | athleto-clients | athleto-interfaces | clients #34, #35; interfaces #25 |
| benefactor-cc | benefactor-clients | benefactor-interfaces | clients #17, #18; interfaces #54 |
| canonical-cloud | canonical-clients | canonical-interfaces | clients #47, #48; interfaces #115, #116 |
| claritas-viz | claritas-clients | claritas-interfaces | clients #16, #17; interfaces #24 |
| cliptown | cliptown-clients | cliptown-interfaces | no Oreslang-titled open PR observed |
| daedalus-fab | daedalus-clients | daedalus-interfaces | clients #32 |
| declarative-migrations | declmig-clients | declmig-interfaces | no Oreslang-titled open PR observed |
| fiducia-cloud | fiducia-clients | fiducia-interfaces | clients #159, #160; interfaces #144 |
| messaging-intel | msgint-clients | msgint-interfaces | clients #66, #67; interfaces #85 |
| opto-sync | opto-sync-clients | opto-sync-interfaces | clients #155, #156; interfaces #37, #38 |
| quaestor-ledger | quaestor-clients | quaestor-interfaces | interfaces #61 |
| sagitta-stack | sagitta-clients | sagitta-interfaces | no Oreslang-titled open PR observed |
| scintilla-run | scintilla-clients | scintilla-interfaces | clients #44; interfaces #62 |
| shared-auth | shared-auth-clients | shared-auth-interfaces | interfaces #117 |
| sonus-auris | sonus-auris-clients | sonus-auris-interfaces | interfaces #49 |

At least **15 distinct organizations** had an Oreslang-titled open draft PR
in at least one member repository. No draft above was counted as a completed
implementation. The actual presence and status of PRs must be refreshed
against GitHub before merging or announcing fleet support.

## Additional shared repository blockers

- `ORESoftware/ores-interfaces`: the `RouteContract.ores` proposal is
  [PR #70](https://github.com/ORESoftware/ores-interfaces/pull/70);
  its checked-in test currently verifies *static declarations and spelling*,
  not native compiler execution. Do not confuse this source module contract
  with the independent TypeSpec/JSON Schema wire authorities.
- `ORESoftware/typespec-json-schema-validator`: owns genuine
  independent TypeSpec/JSON Schema parity and runtime-conformance admission.
  The similarly named `typespec-json-schema-validation` URL was not found.
- `ORESoftware/ores-lambda`: Oreslang execution needs an admitted provider-
  neutral child/runtime adapter, cancellation/timeout propagation, local ingress,
  and real callback/HTTP tests. Rust ingress alone is not Oreslang certification.
- `ORESoftware/ores-middleware`: prove the seven-operation semantic surface
  and error, auth, deadline, cancellation, and backpressure semantics against
  native Oreslang adapters before advertising parity.
- `ORESoftware/ores-locks-and-leases`: Oreslang clients need live lease/fencing
  conformance, atomic expiry semantics, and unsigned-integer serialization
  compatibility rather than just generated declarations.
- `ORESoftware/ores-orm-core`: generated Oreslang projections remain derivative
  only; require independently admitted wire contracts, deterministic emitted
  source, compiler checks, and real database/round-trip witnesses.
- `ORESoftware/api-docs`: add Oreslang route/RPC surface only with canonical
  operation-inventory digest, strict v1/v2 framing tests, malformed fixtures,
  and cross-language cancel/error compatibility.
- `ORESoftware/ores-common-desktop-infra`: future Oreslang desktop/mobile
  adapters must negotiate capabilities and use authenticated IPC/loopback
  controls, one product origin, per-child restarts, route generation swaps,
  and immutable source/runtime revisions. No current Oreslang native
  desktop adapter was observed on its `main` source paths.
- `ORESoftware/ores-comparisons`: Oreslang Gitlinks in PR #134 pin source
  identities across ten stacks. **They do not prove** builds, deployment,
  benchmarks, startup, concurrency, runtime conformance, or GC behavior.

## Release gate (per repository, per backend)

1. Pin Oreslang compiler/runtime and the consumed contract schema revisions.
   Preserve human-authored TypeSpec and JSON Schema as independent authorities.
2. Compile the actual `.ores` source (GraalVM JVM/AOT, LLVM native as applicable)
   and execute the positive and negative fixtures; record exact SHA/IR IDs.
3. Exercise native ingress/egress plus malformed payloads, timeouts, cancellation,
   actor concurrency, ownership/borrows, and resource limits. Reject permissive
   fallbacks and accidental Java/runtime interop.
4. Publish a package only after its exact artifact digest, contract receipt,
   executable runtime receipt, and independent consumer smoke test pass.
5. Keep `planned`, `source-present`, `compiled`, `runtime-tested`,
   `certified`, and `released` as disjoint lifecycle states. A missing receipt
   must fail closed; an organization appearing in the inventory is not release proof.

CI in this repository validates **this snapshot's integrity**. It cannot
magically execute tests inside 36 private siblings with the job's GITHUB_TOKEN.
For an unavailable private source runner, mirror the *exact source Git tree*
into a funded test organization, verify the tree and blob SHAs, execute the
real test suite, and record the exact mirrored commit/run. No result may be
applied to a subsequently modified head.

## Candidate implementation follow-up (2026-10-10)

The original 36-repository `main` baseline above remains a **dated
observation**. The following **unmerged draft branches** now have genuine
derived Oreslang source, but NOT a published SDK, contract admission, or
GraalVM/LLVM runtime certification.

| PR | Concrete implementation | Executed source-only evidence |
| --- | --- | --- |
| [3FA clients #100](https://github.com/3FA-app/3fa-clients/pull/100) | `candidates/oreslang/src/operations.ores` generated from all 10 canonical `operations.json` entries; strict auth, route placeholder, duplicate and drift checks | [private funded CI](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38092816191) |
| [3FA interfaces #51](https://github.com/3FA-app/3fa-interfaces/pull/51) | `candidates/oreslang/src/account_boundaries.ores` generated from **both** independently authored account-v1 peers for the narrow `seat_limit` range, with disagreement and malformed-boundary rejection | [private funded CI](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38092816105) |
| [Messaging Intel clients #67](https://github.com/messaging-intel/msgint-clients/pull/67) | `candidates/oreslang/src/operations.ores` derived from 25 manifest operations, with camelCase names, auth booleans, strict route syntax, duplicate and injection negatives | [private funded CI](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38092816108) |
| [Shared RouteContract #70](https://github.com/ORESoftware/ores-interfaces/pull/70) | Versioned contract, valid/invalid `.ores` consumers, an exact-revision/SHA-pinned real-compiler runner; default CI runs static and missing-compiler fail-closed tests | Public contract/admission CI passed; actual compiler not executed |
| [Graal source compiler #576](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/576) | Native Java/Truffle integration test for importing, type-checking, and executing the shared RouteContract, plus negative cases | Maven execution still required |

**Exact evidence boundary:** The private funded mirror deliberately copied
the **minimum source slice** necessary to run each Python candidate generator
and its negative tests, not all files in the product Git trees. Each mirrored
source blob had to match its origin Git blob SHA, and workflows checked those
immutable SHA identities before executing tests. These results are attributable
to those exact input files only. They do **not** certify the full pull request
head, arbitrary unmirrored dependencies, an actual Oreslang compiler, or a
native artifact.

All candidates live under `candidates/oreslang/` rather than `clients/`
or `langs/`, because existing package and coverage checks treat those
directories as admitted language targets. Do not whitelist candidates into
release packaging to make the tests green.

**Next hard gates:** compile the candidate source with the exact reviewed
Oreslang Java/Graal runtime (and LLVM independently), execute the positive and
negative `.ores` fixtures, validate native import/consumer behavior, admit
the full TypeSpec and JSON Schema current-input parity corpus, and record
compiler/runtime/artifact hashes with provenance. Only after those gates
should the source move from `candidates/` into published language targets.
