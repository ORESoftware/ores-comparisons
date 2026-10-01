# WASM Xprs comparison projects

WASM Xprs uses dedicated no-WASI `wasmx-v1` guest fixtures.

| Fixture org | Source → target | Canary | Current proof |
| --- | --- | --- | --- |
| `ores-dummy-org-rust-wasm-2` | Rust → WASM | `*-web-server.rs` | `wasmx-v1` guest ABI defined |
| `ores-dummy-org-zig-wasm-2` | Zig → WASM | `*-web-server.zig` | `wasmx-v1` guest ABI defined |

Both canaries export `wasmx_main`, disable WASI, and import only the reviewed `wasmx` host functions `input_len`, `input_read`, `output_write`, and `log`. Their runtime manifests use the real `wasmx deploy` and `wasmx invoke` command surface.

WASM Xprs remains `status: registered` because those build/deploy/invoke commands have not yet produced admitted runtime receipts in the comparison ledger. Both lanes must become `runtime_proven` before promotion.
