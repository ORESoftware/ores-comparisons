// GENERATED. DO NOT EDIT.
#![allow(dead_code)]
#[derive(Clone, Debug, PartialEq)]
pub struct FormSubmission {
    pub id: String,
    pub formKey: String,
    pub conversationId: String,
    pub state: String,
    pub audience: String,
    pub createdAt: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ConversationMessage {
    pub id: String,
    pub submissionId: String,
    pub direction: String,
    pub body: String,
    pub createdAt: String,
}
