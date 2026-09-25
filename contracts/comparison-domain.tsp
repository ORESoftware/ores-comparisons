import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons;

@id("Scenario")
enum Scenario {
  http_observability: "http-observability",
  forms_chat_workflow: "forms-chat-workflow",
  cached_rpc: "cached-rpc",
  big_org_example_commerce: "big-org-example-commerce",
  big_org_example_collaboration: "big-org-example-collaboration",
  big_org_example_operations: "big-org-example-operations",
}

@id("WorkStatus")
enum WorkStatus {
  queued,
  running,
  done,
  failed,
}

@id("Payload")
model Payload {
  data: string;
}

@id("Tenant")
model Tenant {
  tenant_id: string;
  slug: string;
  name: string;
  created_at: utcDateTime;
}

@id("Principal")
model Principal {
  principal_id: string;
  tenant_id: string;
  email: string;
  display_name?: string;
  created_at: utcDateTime;
}

@id("WorkItem")
model WorkItem {
  work_item_id: string;
  tenant_id: string;
  scenario: Scenario;
  status: WorkStatus;
  payload: Payload;
  created_at: utcDateTime;
  updated_at: utcDateTime;
}

@id("AuditEvent")
model AuditEvent {
  audit_event_id: string;
  tenant_id: string;
  principal_id?: string;
  event_type: string;
  payload: Payload;
  created_at: utcDateTime;
}

@id("WorkRequest")
model WorkRequest {
  tenant_id: string;
  principal_id?: string;
  scenario: Scenario;
  payload: Payload;
}

@id("WorkResponse")
model WorkResponse {
  work_item_id: string;
  status: WorkStatus;
}

interface ComparisonApi {
  run(request: WorkRequest): WorkResponse;
}
