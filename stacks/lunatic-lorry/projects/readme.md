# Lunatic Lorry comparison projects

Lunatic Lorry uses dedicated WebAssembly fixtures with Lunatic-specific lifecycle semantics.

| Fixture org | Source → target | Canary | Current proof |
| --- | --- | --- | --- |
| `ores-dummy-org-rust-wasm-1` | Rust → `wasm32-wasip1` | `*-web-server.rs` | runtime-compatible Lunatic guest contract defined |
| `ores-dummy-org-zig-wasm-1` | Zig → WASM | `*-web-server.zig` | deterministic WASI artifact only |

The Rust canary follows the actual Lunatic Lorry model: `lunatic = 0.14.1`, `wasm32-wasip1`, mailbox payloads, and a fresh Lunatic actor/process per invocation.

The Zig lane is deliberately not mislabeled as runtime-compatible. Plain WASI stdout does not implement the Lunatic mailbox/bincode invocation ABI; reviewed Zig bindings are the explicit blocker.

Lunatic Lorry remains `status: registered` until both declared lanes are `runtime_proven` with real invocation receipts. Exact pinned canary commits and blockers are in `shared/runtime-execution-evidence.json`.
