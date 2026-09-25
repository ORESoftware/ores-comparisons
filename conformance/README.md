# Conformance

Run:

```sh
npx -y -p @oresoftware/typespec-json-schema-validator@0.1.1 tjsv check \
  --typespec=contracts/comparison-domain.tsp \
  --schema=contracts/comparison-domain.schema.json \
  --instances=contracts/instances \
  --report=artifacts/schema-parity.json

node scripts/generate-contract-artifacts.mjs --check
node conformance/verify-project-layout.mjs
```

The layout verifier requires every project in every stack to carry contracts, conformance, governance and `.ores-compose.yaml` metadata.
