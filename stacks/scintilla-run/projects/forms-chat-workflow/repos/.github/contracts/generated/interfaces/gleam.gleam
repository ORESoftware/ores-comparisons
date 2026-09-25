// GENERATED. DO NOT EDIT.
pub type SubmissionState {
  Received
  Synced
  Completed
}

pub fn submission_state_to_string(value: SubmissionState) -> String {
  case value {
    Received -> "received"
    Synced -> "synced"
    Completed -> "completed"
  }
}

pub type Direction {
  Inbound
  Outbound
}

pub fn direction_to_string(value: Direction) -> String {
  case value {
    Inbound -> "inbound"
    Outbound -> "outbound"
  }
}

pub type FormSubmission {
  FormSubmission(
    id: String,
    formKey: String,
    conversationId: String,
    state: SubmissionState,
    audience: String,
    createdAt: String
  )
}

pub type ConversationMessage {
  ConversationMessage(
    id: String,
    submissionId: String,
    direction: Direction,
    body: String,
    createdAt: String
  )
}
