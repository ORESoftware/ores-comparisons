# BeamScale rpc-graphql

Matched contract workload for RPC/GraphQL-shaped requests. `api-docs` remains the operation/route authority; BeamScale contributes admitted lambda artifacts and deployment metadata.

```sh
bmscl check .
bmscl build . --out-dir dist
bmscl docs-manifest dist --service comparisons-rpc --output dist/lambda-deployment.json
```
