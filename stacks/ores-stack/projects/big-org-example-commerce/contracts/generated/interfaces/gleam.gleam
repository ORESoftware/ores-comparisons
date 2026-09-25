// GENERATED. DO NOT EDIT.
pub type WorkKind {
  Create
  Update
  Sync
}

pub fn work_kind_to_string(value: WorkKind) -> String {
  case value {
    Create -> "create"
    Update -> "update"
    Sync -> "sync"
  }
}

pub type EnterpriseWorkItem {
  EnterpriseWorkItem(
    id: String,
    tenantId: String,
    actorId: String,
    kind: WorkKind,
    payload: String,
    createdAt: String
  )
}
