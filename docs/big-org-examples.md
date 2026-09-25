# Big-org comparison projects

The existing `http-observability`, `forms-chat-workflow`, and `cached-rpc` projects are focused microbenchmarks. This tier adds three larger, organization-shaped workloads per stack without replacing those benchmarks:

| Scenario | BeamScale | Scintilla | ORES Stack |
| --- | --- | --- | --- |
| Commerce | `stacks/beamscale/projects/big-org-example-commerce` | `stacks/scintilla-run/projects/big-org-example-commerce` | `stacks/ores-stack/projects/big-org-example-commerce` |
| Collaboration | `stacks/beamscale/projects/big-org-example-collaboration` | `stacks/scintilla-run/projects/big-org-example-collaboration` | `stacks/ores-stack/projects/big-org-example-collaboration` |
| Operations | `stacks/beamscale/projects/big-org-example-operations` | `stacks/scintilla-run/projects/big-org-example-operations` | `stacks/ores-stack/projects/big-org-example-operations` |

All nine projects use the repository's existing integration IDs for `ores-otel`, `ores-forms`, `opto-sync`, `ores-chat`, `ores-convo`, `ores-rate-limit`, `ores-middleware`, `ores-redis-lru-cache`, `api-docs`, and both ORES SOPS organization identities. The independent TypeSpec and JSON Schema Draft 2020-12 authorities live at `contracts/big-org-example.tsp` and `contracts/big-org-example.schema.json`.

## Native execution shapes

BeamScale uses nested Hosted Gleam Lambda projects with `.ores-lambda.toml` deny-by-default runtime limits and `bmscl-policy.toml` compiler admission. Application P3 workers receive no ambient filesystem/process/socket/secret authority; ORES integrations cross trusted P2 host adapters.

Scintilla uses authored `.scintilla-endpoint.toml` endpoints with `containerized = true`. The Node entrypoints implement the sealed newline-delimited `stdio-json-v1` invocation protocol, echo `invocationId`, propagate `traceparent`, and leave process lifecycle/timeout/recycling to Scintilla.

ORES Stack uses pinned `ores-stack-pub-lib-core::TypedModule` contracts, `.ores-stack.toml`, `api-docs` route maps, and native Rust servers. The shared semantic operation is intended to project to REST/RPC and the authored GraphQL layer (`POST /v1/graphql`) under the `api-docs` contract instead of inferring GraphQL from URL paths.

## SOPS + age

Each project follows the existing repository convention:

```text
.sops.yaml
env/enc/{dev,stage,prod}.env.enc
env/dec/{dev,stage,prod}.env
```

Only ciphertext belongs in Git. Run the repository's existing `just env-init <project>` flow with public dev/stage/prod/recovery age recipients. Private `AGE-SECRET-KEY-...` identities and decrypted environment material must remain outside Git.

## Source snapshots

Each stack also has a `repos/` directory reserved for pinned git submodules when a comparison run needs source-level snapshots of multiple repositories from the corresponding GitHub organization. Normal project builds should prefer immutable package/commit pins rather than following mutable default branches.
