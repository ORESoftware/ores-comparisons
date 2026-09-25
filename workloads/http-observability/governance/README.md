# HTTP observability governance

All three stacks persist the same request-observation and span semantics. `tenant_id` is mandatory on persisted rows. Transport/runtime-specific telemetry adapters may enrich emitted telemetry but may not silently redefine status, duration, request identity, or trace linkage. TypeSpec and JSON Schema are peer authorities; Protobuf numbers are append-only and SQL must converge from both lanes before migration.
