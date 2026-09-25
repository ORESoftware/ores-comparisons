// GENERATED. Do not edit.
use serde::{Deserialize, Serialize};

pub const SCENARIO_VALUES: &[&str] = &["http-observability", "forms-chat-workflow", "cached-rpc", "big-org-example-commerce", "big-org-example-collaboration", "big-org-example-operations"];

pub const WORK_STATUS_VALUES: &[&str] = &["queued", "running", "done", "failed"];

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Payload {
    pub data: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Tenant {
    pub tenant_id: String,
    pub slug: String,
    pub name: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Principal {
    pub principal_id: String,
    pub tenant_id: String,
    pub email: String,
    pub display_name: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkItem {
    pub work_item_id: String,
    pub tenant_id: String,
    pub scenario: String,
    pub status: String,
    pub payload: Payload,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditEvent {
    pub audit_event_id: String,
    pub tenant_id: String,
    pub principal_id: Option<String>,
    pub event_type: String,
    pub payload: Payload,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkRequest {
    pub tenant_id: String,
    pub principal_id: Option<String>,
    pub scenario: String,
    pub payload: Payload,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkResponse {
    pub work_item_id: String,
    pub status: String,
}

