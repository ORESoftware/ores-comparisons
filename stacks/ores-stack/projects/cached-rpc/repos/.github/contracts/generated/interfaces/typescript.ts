// GENERATED. DO NOT EDIT.
export type CacheState = "hit" | "miss" | "bypass";

export interface RpcInvocation {
  id: string;
  operation: string;
  cacheKey: string;
  cacheState: CacheState;
  durationMs: number;
  createdAt: string;
}

export interface CacheEntry {
  cacheKey: string;
  payloadJson: string;
  expiresAt: string;
  createdAt: string;
}
