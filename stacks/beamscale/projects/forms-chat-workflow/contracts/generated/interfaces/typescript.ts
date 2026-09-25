// GENERATED. DO NOT EDIT.
export interface FormSubmission {
  id: string;
  formKey: string;
  conversationId: string;
  state: string;
  audience: string;
  createdAt: string;
}

export interface ConversationMessage {
  id: string;
  submissionId: string;
  direction: string;
  body: string;
  createdAt: string;
}
