namespace OresComparisons.FormService;

enum SubmissionStatus {
  draft: "draft",
  submitted: "submitted",
  processed: "processed",
}

model FormDefinition {
  id: string;
  tenant_id: string;
  form_key: string;
  title: string;
  version: int32;
  active: boolean;
  created_at: string;
}

model FormSubmission {
  id: string;
  tenant_id: string;
  form_definition_id: string;
  status: SubmissionStatus;
  response_json: string;
  created_at: string;
}

model SubmitFormRequest {
  tenant_id: string;
  form_definition_id: string;
  response_json: string;
}

model SubmitFormResponse {
  submission: FormSubmission;
}

interface FormService {
  op submit_form(request: SubmitFormRequest): SubmitFormResponse;
}
