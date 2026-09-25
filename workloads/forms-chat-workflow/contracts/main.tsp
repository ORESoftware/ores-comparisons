import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.FormsChatWorkflow;

@id("WorkflowStatus")
enum WorkflowStatus {
  draft: "draft",
  awaiting_message: "awaiting_message",
  completed: "completed",
}

@id("WorkflowMessageKind")
enum WorkflowMessageKind {
  text: "text",
  system: "system",
}

@id("WorkflowRun")
model WorkflowRun {
  id: string;
  tenant_id: string;
  form_key: string;
  status: WorkflowStatus;
  revision: int32;
  created_at: string;
}

@id("FormResponse")
model FormResponse {
  id: string;
  tenant_id: string;
  workflow_run_id: string;
  response_json: string;
  created_at: string;
}

@id("WorkflowMessage")
model WorkflowMessage {
  id: string;
  tenant_id: string;
  workflow_run_id: string;
  sender_id: string;
  kind: WorkflowMessageKind;
  body: string;
  sequence: int32;
  created_at: string;
}

@id("AdvanceWorkflowRequest")
model AdvanceWorkflowRequest {
  tenant_id: string;
  workflow_run_id: string;
  response_json: string;
  message_body: string;
}

@id("AdvanceWorkflowResponse")
model AdvanceWorkflowResponse {
  run: WorkflowRun;
}

interface FormsChatWorkflowService {
  op advance_workflow(request: AdvanceWorkflowRequest): AdvanceWorkflowResponse;
}
