# BeamScale / forms-chat-workflow

Form/chat workflow facade; service calls are admitted through platform adapters.

This is an admitted Hosted Gleam project. The worker exposes the current
`bmscl_sdk` `handle(Request, Context) -> Response` ABI. It never opens a
socket, spawns a process, reads a file, or reads ambient environment state.

The integration URLs in `.env.example` belong to trusted platform adapters.
Inside the worker, observability is emitted through `ctx.log`; same-application
fan-out can use `ctx.cluster`. This keeps the example faithful to BeamScale's
capability boundary while preserving the same logical integration graph used by
Scintilla and ORES Stack.

## Run

```sh
bmscl check . --policy ./bmscl-policy.toml --worker-config ./.ores-lambda.toml
bmscl dev .
bmscl build . --out-dir ./dist --policy ./bmscl-policy.toml --worker-config ./.ores-lambda.toml
bmscl deploy ./dist --project comparison-forms-chat-workflow --environment dev --dry-run
```

For production, package/sign the artifacts and deploy without `--dry-run`.
