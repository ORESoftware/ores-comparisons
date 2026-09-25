# ores-comparisons

Deployable, side-by-side example projects for the three ORES application stacks.

| Stack | Execution model | Projects |
| --- | --- | --- |
| [BeamScale](https://github.com/beamscale) | admitted Gleam -> Erlang/BEAM; one host-supervised actor/process per invocation | `http-observability`, `forms-chat-workflow`, `cached-rpc` |
| [Scintilla](https://github.com/scintilla-run) | polyglot functions in containers/sub-processes | `http-observability`, `forms-chat-workflow`, `cached-rpc` |
| [ORES Stack](https://github.com/ores-stack) | Rust native servers + WASM/page assets + RPC/API generation | `http-observability`, `forms-chat-workflow`, `cached-rpc` |

Every project uses the same comparison contract and declares adapters for:

- https://github.com/ores-otel
- https://github.com/ores-forms
- https://github.com/opto-sync
- https://github.com/ores-chat
- https://github.com/ores-convo
- https://github.com/ores-rate-limit
- https://github.com/ORESoftware/ores-middleware
- https://github.com/ores-redis-lru-cache
- https://github.com/ORESoftware/api-docs
- https://github.com/ORESoftware/ores-sops
- https://github.com/ores-sops

The duplicated SOPS references are intentional: `ORESoftware/ores-sops` is the
currently readable canonical implementation while `ores-sops` is the target
organization boundary. Example code treats them as one secret-management
contract, not two competing formats.

## Layout

```text
stacks/
  beamscale/projects/{http-observability,forms-chat-workflow,cached-rpc}
  scintilla-run/projects/{http-observability,forms-chat-workflow,cached-rpc}
  ores-stack/projects/{http-observability,forms-chat-workflow,cached-rpc}
shared/
  integrations.json
scripts/
  verify_examples.py
  bootstrap-env.sh
```

Each project contains:

- `comparison.toml` — scenario, deployment commands, and the complete integration set.
- `.sops.yaml` — exact dev/stage/prod age-recipient policy template.
- `env/enc/` — encrypted dotenv files live here after bootstrap.
- `env/dec/` — runtime-only plaintext; Git ignores everything except its guard file.
- `.env.example` — names only / non-secret local defaults.
- stack-native source and deployment configuration.

## Quick start

Enter the Nix shell:

```sh
nix develop
just verify
```

Initialize encrypted environment files for one project after supplying **public**
age recipients:

```sh
export DEV_AGE_RECIPIENT=age1...
export STAGE_AGE_RECIPIENT=age1...
export PROD_AGE_RECIPIENT=age1...
export RECOVERY_AGE_RECIPIENT=age1...

just env-init stacks/beamscale/projects/http-observability
```

No private age key and no decrypted `*.env` file belongs in Git.

Then use the project's README. The stack CLIs remain the deployment authorities:

```sh
bmscl check . && bmscl build . --out-dir dist && bmscl deploy dist --project comparison-http
scintilla build --project . --out-dir .scintilla && scintilla deploy --project . --out-dir .scintilla
ores-stack check && ores-stack build
```

## Comparison philosophy

The projects intentionally hold workload semantics constant while allowing the
runtime to differ. Telemetry, forms/sync/chat, rate limiting/cache, API docs,
middleware, and secret activation are represented as explicit ports. That makes
latency, cold-start, memory, isolation, artifact size, deployment behavior, and
cost measurements attributable to the stack rather than to three unrelated apps.

Run `python3 scripts/verify_examples.py` before adding another project. It
fails closed if a project drops an integration, loses its encrypted/decrypted env
boundary, or misses the stack-specific deployment contract.
