# LiteGraph grandaddy / daddy / child generation contract

The comparison must **not** mark LiteGraph executable or isolation-certified from this document or the synthetic fixture. It is a rigorous admission contract for a future live benchmark; see `shared/stack-catalog.json` (`litegraph.status=registered`, `benchmark_executable=null`).

## Required runtime graph

```text
                  GRANDADDY (long-lived proxy/load balancer)
 external HTTP -> listener PID stable across both replacements
                      |    authenticated workload identity
                      v
                DADDY (middleware host generation M)
                    /   \    candidate validated before switch
                  M.1   M.2 -> old M.1 drains in-flight requests
                      |    structured, versioned IPC
                      v
                CHILD (worker/actor generation W)
                    /   \    Wasmtime per-invocation store for untrusted code
                  W.7   W.8 -> old W.7 drains in-flight requests
                      |
                      v
                   CPU/GPU brokers (privileged host only)
```

- **Grandaddy:** persistent TCP/QUIC listener, connection acceptance, TLS termination, admission and load balancing. Never runs untrusted guest code. Prove stable process ID **and listener identifier** while testing both reload operations.
- **Daddy:** independently deployed **trusted** middleware process or service; supports atomic cutover to a newly validated generation without restarting grandaddy. A request keeps its middleware generation for its full lifecycle. Authentication, policy, rate limiting and trace context may not be bypassed during rollout. ORES in-process baseline middleware currently requires a router restart; do not call that baseline hot reload.
- **Child:** independent per-tenant/function/version worker generation, promoted only on expected-revision CAS after digest, ABI and capability admission. Existing requests finish on the old generation. New requests use the new generation. Never share raw guest pointers or memory across unrelated generations/tenants.
- **Guest execution:** compile Rust/C++/compatible LLVM-Oreslang source to a **core Wasm** module that obeys `litegraph.core-wasm/v1`; long-term target is the WIT Component Model in `litegraph-wit`. Wasmtime host provides new Store, fresh linear memory, memory/fuel/epoch limits, capability-denied imports and bounded input/output. This is Wasm guest isolation, **not** an OS sandbox guarantee for a compromised host.
- **Native/GPU:** C++ and Rust native binaries, LLVM JIT, CUDA, ROCm, Metal and Vulkan stay in separate privileged native workers/accelerator brokers, with process/microVM sandbox admission where untrusted. Native `.so`/`.dll` hot-loading into a shared trusted parent is not a tenant isolation strategy. A CUDA kernel must never receive a guest pointer or shared mutable GPU state across tenants without verified isolation.
- **ABIs and ownership:** use copied serialized messages (or narrowly constrained owned Wasm memory/handles) with explicit version and length checks, resource quotas, capability revocation, deterministic cleanup and generation-fenced result callbacks. Avoid C++ ABI boundaries and arbitrary raw-pointer FFI across security domains.

## Required hot-reload test

1. Start real proxy, middleware M1, worker W1, and send a request **A** that intentionally remains in flight.
2. Build/admit candidate W2, reject wrong tenant/digest/ABI/revision, atomically promote W2; request **A** completes using W1; new request **B** runs W2, with no memory bleed.
3. Repeat with an in-flight middleware request, promote M2 independently, and verify M1 finishes its request and new requests receive M2 policy. Long-lived proxy process ID and listening socket remain stable.
4. Inject compile failures, traps, denied imports, bad middleware policy, CPU starvation, crash, timeout, client cancellation, and abrupt controller death. Prove failed preparation does not mutate the active generation; prove drain timeout is reported and safety holds.
5. Verify two hostile tenants cannot read each other's Wasm memory or resident model handles; do **not** claim GPU hardware isolation without device-backed tests.
6. Record commit SHAs, ABI versions, process IDs, listener IDs, generation ids, test steps and worker/artifact digests from the **real executed run**. Compare to the same Rust/C++/Oreslang source-language workload where backends exist.

`tools/verify_litegraph_generations.rs` validates a synthetic fixture and the *shape* of supplied receipts, including negative mutation tests. It **cannot independently certify** an external GitHub Actions URL, signed attestation, host sandbox or live workload. Only a job that actually launches the proxy, middleware and worker and verifies the above behavior can produce runtime evidence; unrelated or zero-step workflows must not count.

## Current implementation gaps

- `litegraph-runtime`: per-tenant Wasm sandbox exists and generation-slot implementation is under review.
- `litegraph-node`: single-tenant Wasm execution exists; hot activation controller is under review and must be independently tested.
- `litegraph-router.rs`: current ORES middleware stack is fixed at startup; no independently deployable middleware rollout is verified.
- `ores-comparisons`: LiteGraph remains **registered**, not materialized/certified, until a full three-generation live proof is executed.
