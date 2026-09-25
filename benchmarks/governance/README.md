# Benchmark governance

Benchmark observations and smoke receipts are executable data contracts.

- TypeSpec and JSON Schema Draft 2020-12 are peer authorities.
- The repository's static parity/corpus gate validates this contract in mandatory CI.
- Full `ORESoftware/typespec-json-schema-validator` verification runs when private cross-repo access is configured.
- Latencies are integer microseconds.
- Memory and artifact sizes are bytes.
- Estimated cost is integer micro-USD per one million requests.
- Missing metrics remain absent; the harness never substitutes zero.
- ORES Stack app examples use `artifact-handoff` deploy mode until a real infra target manifest exists.
