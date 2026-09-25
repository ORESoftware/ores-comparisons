import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.CachedRpc;

@id("CacheState")
enum CacheState {
  fresh: "fresh",
  stale: "stale",
  bypassed: "bypassed",
}

@id("RpcMethod")
model RpcMethod {
  id: string;
  tenant_id: string;
  operation_key: string;
  transport: string;
  active: boolean;
  created_at: string;
}

@id("RpcCacheEntry")
model RpcCacheEntry {
  id: string;
  tenant_id: string;
  rpc_method_id: string;
  cache_key: string;
  response_json: string;
  state: CacheState;
  expires_at: string;
  revision: int32;
  created_at: string;
}

@id("InvokeCachedRpcRequest")
model InvokeCachedRpcRequest {
  tenant_id: string;
  operation_key: string;
  cache_key: string;
  request_json: string;
}

@id("InvokeCachedRpcResponse")
model InvokeCachedRpcResponse {
  entry: RpcCacheEntry;
}

interface CachedRpcService {
  op invoke_cached(request: InvokeCachedRpcRequest): InvokeCachedRpcResponse;
}
