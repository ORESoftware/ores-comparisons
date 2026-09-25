# BeamScale comparison projects

All three projects are Hosted Gleam workloads admitted by `bmscl-compiler`.
They use the trusted `bmscl_sdk` request/context/response ABI and preserve the
one tenant-owned BEAM process per invocation rule.

- `http-observability` — request/response + platform logging/telemetry.
- `forms-chat-workflow` — forms/sync/chat/conversation integration facade.
- `cached-rpc` — api-docs operation identity + cache/RPC integration facade.

Use `bmscl check`, `bmscl dev`, `bmscl build`, and `bmscl deploy` inside
each project.
