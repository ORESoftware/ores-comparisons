// GENERATED. DO NOT EDIT.
#![allow(dead_code)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SubmissionState {
    Received,
    Synced,
    Completed,
}

impl SubmissionState {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Received => "received",
            Self::Synced => "synced",
            Self::Completed => "completed",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Direction {
    Inbound,
    Outbound,
}

impl Direction {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Inbound => "inbound",
            Self::Outbound => "outbound",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct FormSubmission {
    pub id: String,
    pub formKey: String,
    pub conversationId: String,
    pub state: SubmissionState,
    pub audience: String,
    pub createdAt: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ConversationMessage {
    pub id: String,
    pub submissionId: String,
    pub direction: Direction,
    pub body: String,
    pub createdAt: String,
}

