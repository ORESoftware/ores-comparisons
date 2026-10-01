# Runtime topology verifier contract

Tracking: #74.

The comparison lane must fail closed when an organization's declared runtime-bearing repositories are absent from the `ores-compose` topology. The durable verifier belongs in Rust and compares the org manifest/runtime inventory against compose services and dependency ordering; it must not rely on prose tables or Python glue.

`tools/verify_runtime_topology.rs` is the admission gate. Runtime-bearing repository kinds are `application`, `service`, `worker`, and `frontend`. Every declared runtime repository must exist as a compose service, every runtime dependency declared by `org.manifest.json` must be preserved directly or transitively by `depends_on`, the umbrella `app` must be present, and initialized runtime repositories must match their exact superproject gitlinks. Whole-fleet verification also rejects sibling stacks that exercise different runtime repository sets for the same scenario.

The per-project runtime workflow executes this gate after exact gitlink initialization and before `ores-compose check`, `plan`, or `up`. A passing target emits `ores.comparisons.runtime-topology-proof/v1` evidence binding the superproject revision, `.github` gitlink, org-manifest blob, compose-manifest blob, verifier blob, runtime repository set, and compose service set. The topology proof is uploaded separately from the runtime execution proof so an under-scoped graph cannot reach runtime execution.

A future aggregate-proof revision should cryptographically bind each topology-proof artifact into `runtime-proof-set-18`; until that final binding lands, #74 remains open. The execution lane itself is already fail-closed because runtime proof cannot start unless the topology verifier succeeds.

Sibling-stack parity checks compare equivalent FaaS families without assuming identical implementation details. The verifier must surface under-scoped operations such as missing ingest/automation/ops services rather than marking a partial compose graph complete.
