// GENERATED. DO NOT EDIT.
pub type FormSubmission {
  FormSubmission(
    id: String,
    formKey: String,
    conversationId: String,
    state: String,
    audience: String,
    createdAt: String
  )
}

pub type ConversationMessage {
  ConversationMessage(
    id: String,
    submissionId: String,
    direction: String,
    body: String,
    createdAt: String
  )
}
