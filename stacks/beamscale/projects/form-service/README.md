# BeamScale form-service

Matched form workload for the BeamScale actor-lambda stack. The lambda is intentionally capability-minimal: host middleware performs telemetry, rate-limit/cache policy, secret resolution, and service adapters for `ores-forms`/`opto-sync`; tenant code receives only the admitted request/context.

```sh
bmscl check .
bmscl build . --out-dir dist
bmscl deploy dist --project ores-comparisons-form --environment dev --dry-run
```
