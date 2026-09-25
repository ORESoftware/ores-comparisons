// GENERATED. DO NOT EDIT.
#![allow(dead_code)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Outcome {
    Ok,
    Error,
}

impl Outcome {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Ok => "ok",
            Self::Error => "error",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct RequestObservation {
    pub id: String,
    pub requestId: String,
    pub route: String,
    pub statusCode: i64,
    pub durationMs: i64,
    pub outcome: Outcome,
    pub createdAt: String,
}

