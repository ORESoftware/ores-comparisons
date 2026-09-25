import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.FormService;

@id("SubmissionStatus")
enum SubmissionStatus {
  draft: "draft",
  submitted: "submitted",
  processed: "processed",
}

@id("FormDefinition")
model FormDefinition {
  id: string;
  tenant_id: string;
  form_key: string;
  title: string;
  version: int32;
  active: boolean;
  created_at: string;
}

@id("FormSubmission")
model FormSubmission {
  id: string;
  tenant_id: string;
  form_definition_id: string;
  status: SubmissionStatus;
  response_json: string;
  created_at: string;
}

@id("SubmitFormRequest")
model SubmitFormRequest {
  tenant_id: string;
  form_definition_id: string;
  response_json: string;
}

@id("SubmitFormResponse")
model SubmitFormResponse {
  submission: FormSubmission;
}

interface FormService {
  op submit_form(request: SubmitFormRequest): SubmitFormResponse;
}
