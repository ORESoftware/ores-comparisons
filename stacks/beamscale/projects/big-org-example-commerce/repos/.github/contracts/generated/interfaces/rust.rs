// GENERATED. DO NOT EDIT.
#![allow(dead_code)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum WorkKind {
    Create,
    Update,
    Sync,
}

impl WorkKind {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Create => "create",
            Self::Update => "update",
            Self::Sync => "sync",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct EnterpriseWorkItem {
    pub id: String,
    pub tenantId: String,
    pub actorId: String,
    pub kind: WorkKind,
    pub payload: String,
    pub createdAt: String,
}
