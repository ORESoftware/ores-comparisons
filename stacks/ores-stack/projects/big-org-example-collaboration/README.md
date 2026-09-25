# big-org-example-collaboration — ORES Stack

A native Rust collaboration service with a compile-time `TypedModule` boundary. Forms, offline sync, chat, and encrypted-conversation adapters are exposed through approved middleware/context injection; ambient provider clients are not part of the application interface.

The module requests explicit provider-neutral capabilities, `api-docs` owns route identity, the shared TypeSpec/JSON Schema pair owns cross-stack payload semantics, and SOPS+age owns secrets.

```sh
ores-stack check
ores-stack build
cargo run --release --locked
```
