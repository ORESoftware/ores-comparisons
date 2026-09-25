# Conformance

Install the pinned toolchain declared in `package.json`, then run:

```sh
npm install --no-audit --no-fund
mkdir -p artifacts
npm run contracts:parity
npm run contracts:generated-check
npm run contracts:layout
```

Install scripts are intentionally enabled because the pinned `flags-2-env` dependency builds its native Node binding during installation. The validator itself is sourced from the exact `ORESoftware/typespec-json-schema-validator` commit recorded in `governance/toolchain.lock.json`.

`contracts:parity` treats TypeSpec and authored JSON Schema Draft 2020-12 as peer authorities and fails closed on unexplained structural or behavioral disagreement. The layout verifier requires every project in every stack to carry contracts, conformance, governance and `.ores-compose.yaml` metadata.
