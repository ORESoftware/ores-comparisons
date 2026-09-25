# BeamScale realtime-chat

Matched chat workload. Message requests execute as fresh admitted BEAM invocations; durable conversation state stays behind `ores-chat`, `ores-convo`, and `opto-sync` service boundaries rather than BEAM process memory.

```sh
bmscl check .
bmscl build . --out-dir dist
bmscl deploy dist --project ores-comparisons-chat --environment dev --dry-run
```
