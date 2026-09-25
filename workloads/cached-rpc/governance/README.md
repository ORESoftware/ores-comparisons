# Cached RPC governance

`operation_key` is the stable api-docs/RPC identity; cache keys are tenant scoped. A cache hit may change latency but not RPC response semantics. `revision` is monotonic and cache state transitions must remain explicit. Protobuf numbers are locked, TypeSpec and JSON Schema are peer authorities, and both SQL lanes must converge before schema migration.
