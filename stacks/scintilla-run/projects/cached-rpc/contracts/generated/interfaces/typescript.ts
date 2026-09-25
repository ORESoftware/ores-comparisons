// GENERATED. DO NOT EDIT.
export interface RpcInvocation {
  id: string;
  operation: string;
  cacheKey: string;
  cacheState: string;
  durationMs: number;
  createdAt: string;
}

export interface CacheEntry {
  cacheKey: string;
  payloadJson: string;
  expiresAt: string;
  createdAt: string;
}
