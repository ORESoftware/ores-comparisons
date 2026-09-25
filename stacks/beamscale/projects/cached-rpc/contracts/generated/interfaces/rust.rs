// GENERATED. DO NOT EDIT.
#![allow(dead_code)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CacheState {
    Hit,
    Miss,
    Bypass,
}

impl CacheState {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Hit => "hit",
            Self::Miss => "miss",
            Self::Bypass => "bypass",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct RpcInvocation {
    pub id: String,
    pub operation: String,
    pub cacheKey: String,
    pub cacheState: CacheState,
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

