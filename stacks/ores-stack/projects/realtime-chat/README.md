# ORES Stack realtime-chat

Standalone Rust server version of the matched chat workload. Durable chat/conversation state remains in the shared service layer; the process can be restarted without losing authoritative state.

```sh
ores-stack check
ores-stack build --no-exec
cargo check
```
