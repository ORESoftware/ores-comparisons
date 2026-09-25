// GENERATED. DO NOT EDIT.
 #![allow(dead_code)]
 #[derive(Clone, Debug, PartialEq)]
 pub struct RpcInvocation {
     pub id: String,
     pub operation: String,
     pub cacheKey: String,
     pub cacheState: String,
     pub durationMs: i64,
     pub createdAt: String,
 }

 #[derive(Clone, Debug, PartialEq)]
 pub struct CacheEntry {
     pub cacheKey: String,
     pub payloadJson: String,
     pub expiresAt: String,
     pub createdAt: String,
 }
