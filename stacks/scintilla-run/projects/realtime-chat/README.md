# Scintilla realtime-chat

Container/subprocess version of the matched chat workload. Two endpoints model publish and history so process startup, isolation, cache behavior, and host middleware can be compared with BeamScale actors and the Rust server.

```sh
scintilla build --project . --out-dir .scintilla
scintilla deploy --project . --out-dir .scintilla --dry-run
```
