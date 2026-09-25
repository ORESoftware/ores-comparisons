// GENERATED. Do not edit.

export type Scenario = "http-observability" | "forms-chat-workflow" | "cached-rpc" | "big-org-example-commerce" | "big-org-example-collaboration" | "big-org-example-operations";

export type WorkStatus = "queued" | "running" | "done" | "failed";

export interface Payload {
  data: string;
}

export interface Tenant {
  tenant_id: string;
  slug: string;
  name: string;
  created_at: string;
}

export interface Principal {
  principal_id: string;
  tenant_id: string;
  email: string;
  display_name?: string;
  created_at: string;
}

export interface WorkItem {
  work_item_id: string;
  tenant_id: string;
  scenario: Scenario;
  status: WorkStatus;
  payload: Payload;
  created_at: string;
  updated_at: string;
}

export interface AuditEvent {
  audit_event_id: string;
  tenant_id: string;
  principal_id?: string;
  event_type: string;
  payload: Payload;
  created_at: string;
}

export interface WorkRequest {
  tenant_id: string;
  principal_id?: string;
  scenario: Scenario;
  payload: Payload;
}

export interface WorkResponse {
  work_item_id: string;
  status: WorkStatus;
}
