// GENERATED. DO NOT EDIT.
pub type RpcInvocation {
  RpcInvocation(
    id: String,
    operation: String,
    cacheKey: String,
    cacheState: String,
    durationMs: Int,
    createdAt: String
  )
}

pub type CacheEntry {
  CacheEntry(
    cacheKey: String,
    payloadJson: String,
    expiresAt: String,
    createdAt: String
  )
}
