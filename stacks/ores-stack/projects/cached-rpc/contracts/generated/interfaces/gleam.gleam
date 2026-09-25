// GENERATED. DO NOT EDIT.
pub type CacheState {
  Hit
  Miss
  Bypass
}

pub fn cache_state_to_string(value: CacheState) -> String {
  case value {
    Hit -> "hit"
    Miss -> "miss"
    Bypass -> "bypass"
  }
}

pub type RpcInvocation {
  RpcInvocation(
    id: String,
    operation: String,
    cacheKey: String,
    cacheState: CacheState,
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

