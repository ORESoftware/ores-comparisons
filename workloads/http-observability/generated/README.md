# Generated — do not edit

`./scripts/admit-and-generate.sh workloads/http-observability` runs `tjsv`, retains its TypeSpec witness/Contract IR/report, generates Protobuf + SQL from the TypeSpec witness, generates Rust/TypeScript/validation schema + SQL from authored JSON Schema, and requires SQL convergence before emitting `sql/001_schema.sql`.
