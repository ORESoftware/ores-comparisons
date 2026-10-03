# Fleet proof gate

This directory documents the cross-fleet meta-proof boundary tracked by GitHub issue #30. The executable authority is the dependency-free Rust tool at `tools/fleet_proof.rs`; canonical fixtures and mutation tests live as typed Rust values instead of a second JSON/Python validation authority.

The proof gate requires:

- explicit evidence states: `passed`, `failed`, `blocked`, `skipped`, and `not-run`;
- nonzero executed work for `passed` / `failed`, with state-specific blocker/failure/skip/not-run reasons;
- immutable GitHub repository + exact 40-hex revision identity from the current checkout;
- SHA-256 bindings for dependency topology (`.gitmodules`), project configuration, and the governed toolchain lock;
- exact source/dependency bindings for external and test-organization evidence;
- agreement across stack, compose, auth, middleware, rate-limit, and telemetry policy views;
- one tenant-safe correlation chain across ingress → server/lambda → queue → database;
- bounded telemetry cardinality and payload size;
- a runtime-generated canary that must not leak into logs, traces, build output, or receipts;
- model→implementation refinement across queued → admitted → running → ready → draining → stopped;
- an executable backup/restore drill that creates deterministic bytes, snapshots them read-only under ignored `tmp/`, deletes the original, restores it, proves SHA-256 equality, and measures recovery time against an explicit RTO.

CI compiles the Rust verifier and its inline mutation tests with the pinned Rust 1.88 toolchain, runs the tests, executes the live proof against the exact checkout, and retains `artifacts/fleet-proof.json`. The tool accepts no command-line options and no credentials.

This proof is meta-evidence; it does not replace the private 18-project runtime proof required by issue #55.
