# Conformance

Install the pinned toolchain declared in `package.json`, then run:

```sh
npm install --ignore-scripts --no-audit --no-fund
mkdir -p artifacts
npm run contracts:parity
npm run contracts:generated-check
npm run contracts:layout
```

`contracts:parity` invokes the `tjsv` binary from the exact `ORESoftware/typespec-json-schema-validator` commit recorded in `governance/toolchain.lock.json`. The validator treats TypeSpec and authored JSON Schema Draft 2020-12 as peer authorities and fails closed on unexplained structural or behavioral disagreement.

The layout verifier requires every project in every stack to carry contracts, conformance, governance and `.ores-compose.yaml` metadata.
