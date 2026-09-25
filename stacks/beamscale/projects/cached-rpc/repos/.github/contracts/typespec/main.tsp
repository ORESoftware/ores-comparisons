import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.CachedRpc;

@id("CacheState")
enum CacheState {
  hit: "hit",
  miss: "miss",
  bypass: "bypass",
}

@id("RpcInvocation")
model RpcInvocation {
  id: string;
  operation: string;
  cacheKey: string;
  cacheState: CacheState;
  durationMs: integer;
  createdAt: string;
}

@id("CacheEntry")
model CacheEntry {
  cacheKey: string;
  payloadJson: string;
  expiresAt: string;
  createdAt: string;
}
