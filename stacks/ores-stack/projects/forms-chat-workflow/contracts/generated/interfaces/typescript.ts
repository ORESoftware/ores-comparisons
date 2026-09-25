// GENERATED. DO NOT EDIT.
export type SubmissionState = "received" | "synced" | "completed";

export type Direction = "inbound" | "outbound";

export interface FormSubmission {
  id: string;
  formKey: string;
  conversationId: string;
  state: SubmissionState;
  audience: string;
  createdAt: string;
}

export interface ConversationMessage {
  id: string;
  submissionId: string;
  direction: Direction;
  body: string;
  createdAt: string;
}

