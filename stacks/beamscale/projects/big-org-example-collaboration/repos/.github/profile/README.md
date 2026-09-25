# big-org-example-collaboration — BeamScale

A collaboration workload where short-lived P3 actors handle form submissions, sync events, chat commands, and encrypted-conversation metadata while P1/P2 stay hot.

`ores-forms`, `opto-sync`, `ores-chat`, and `ores-convo` define the client/application surface. Trusted P2 adapters inject only approved operations into hosted workers; rate limiting, Redis LRU caching, and OTEL are middleware concerns. `api-docs` plus the shared TypeSpec/JSON Schema pair own request/response identity, and SOPS+age owns environment material.

```sh
bmscl check .
bmscl build . --out-dir dist
bmscl deploy dist --project big-org-example-collaboration --environment dev --dry-run
bmscl deploy dist --project big-org-example-collaboration --environment dev
```
