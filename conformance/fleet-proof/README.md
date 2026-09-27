# Fleet proof evidence gate

This directory holds synthetic **conformance evidence**, not production runtime proof. The gate makes cross-fleet evidence semantics executable before individual product receipts are trusted.

The v1 evidence envelope requires:

- an explicit state: `passed`, `failed`, `blocked`, `skipped`, or `not-run`;
- a nonzero executed-step count for `passed` and `failed`; zero-step evidence can never certify;
- immutable repository + 40-hex commit identity;
- SHA-256 dependency, configuration, and toolchain identities;
- exact source/dependency binding for external or test-organization evidence;
- agreement across stack, compose, auth, middleware, rate-limit, and telemetry policy views;
- one tenant-safe correlation chain across ingress → server → queue → database;
- bounded telemetry attribute cardinality and serialized event size;
- a synthetic canary secret that must not appear in logs, traces, build output, or receipts.

`scripts/verify_fleet_proof_evidence.py` emits a small proof-gate receipt. The unit tests deliberately mutate the clean fixture and require deterministic finding IDs for every planted violation. The verifier is read-only and must never mutate the evidence document it audits.

GitHub issue #30 is split into independent proof surfaces. `evidence-valid.v1.json` covers source-bound evidence and telemetry safety. `refinement-recovery-valid.v1.json` connects a declared lifecycle model to executable implementation events and proves an immutable backup restores byte-identical artifact content within an explicit recovery-time objective. Both verifiers are read-only and emit separate receipts so one proof cannot mask failure in the other.
