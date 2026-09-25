import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.FormsChatWorkflow;

@id("SubmissionState")
enum SubmissionState {
  received: "received",
  synced: "synced",
  completed: "completed",
}

@id("Direction")
enum Direction {
  inbound: "inbound",
  outbound: "outbound",
}

@id("FormSubmission")
model FormSubmission {
  id: string;
  formKey: string;
  conversationId: string;
  state: SubmissionState;
  audience: string;
  createdAt: string;
}

@id("ConversationMessage")
model ConversationMessage {
  id: string;
  submissionId: string;
  direction: Direction;
  body: string;
  createdAt: string;
}
