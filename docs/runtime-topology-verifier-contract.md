# Runtime topology verifier contract

Tracking: #74.

The comparison lane must fail closed when an organization's declared runtime-bearing repositories are absent from the `ores-compose` topology. The durable verifier belongs in Rust and compares the org manifest/runtime inventory against compose services and dependency ordering; it must not rely on prose tables or Python glue.

A topology receipt binds the exact org-manifest revision, compose manifest revision, selected sibling stack/family, normalized service/repository mapping, dependency-order evidence, and verifier build identity. Missing runtime services, undeclared compose services that affect the runtime path, or dependency-order violations stop promotion.

Sibling-stack parity checks compare equivalent FaaS families without assuming identical implementation details. The verifier must surface under-scoped operations such as missing ingest/automation/ops services rather than marking a partial compose graph complete.