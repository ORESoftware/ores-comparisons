# Tenant host-isolation proof

This gate defines the minimum host evidence required before a FaaS runtime may claim production-grade tenant process isolation.

The evidence is deliberately runtime-neutral: GraalVM, Wasm, BEAM/Lunatic, Pony, Scintilla, Beamscale and LiteGraph may use different execution engines, but a shared-kernel production tenant boundary must prove the same host controls. A microVM profile is stronger and still proves the guest-side controls where applicable.

Required evidence includes:

- a host-created stable `runtime_id`; tenant input cannot choose a PID/cgroup path as lifecycle authority;
- dedicated cgroup-v2 scope and identity-bound containment via `ORESoftware/ores-proc-isolation-cli` or a semantically equivalent implementation;
- non-root workload identity, zero ambient/effective capabilities, and `no_new_privs`;
- seccomp enforcement and a filesystem restriction layer (Landlock, mount namespace/read-only mounts, or a stronger boundary);
- PID, mount, IPC and network isolation appropriate to the runtime;
- explicit CPU, memory, PID and I/O ceilings;
- cloud instance-metadata access denied unless explicitly brokered;
- sibling-tenant traffic denied by default and egress constrained by policy;
- executable/artifact digest admission before launch;
- containment receipts that do not disclose raw cgroup paths;
- a stronger microVM/VM boundary when the deployment profile requires mutually hostile-tenant isolation.

`check.mjs` is dependency-free and fail-closed. It validates fixtures and real evidence records supplied by runtime certification jobs. Passing a static fixture does **not** prove a production host: platform CI must generate evidence from the live sandbox/worker it is certifying.
