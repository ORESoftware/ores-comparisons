namespace OresComparisons.RealtimeChat;

enum MessageKind {
  text: "text",
  image: "image",
  system: "system",
}

model Conversation {
  id: string;
  tenant_id: string;
  topic: string;
  created_at: string;
}

model ChatMessage {
  id: string;
  tenant_id: string;
  conversation_id: string;
  sender_id: string;
  kind: MessageKind;
  body: string;
  sequence: int32;
  created_at: string;
}

model PublishMessageRequest {
  tenant_id: string;
  conversation_id: string;
  sender_id: string;
  kind: MessageKind;
  body: string;
}

model PublishMessageResponse {
  message: ChatMessage;
}

interface ChatService {
  op publish_message(request: PublishMessageRequest): PublishMessageResponse;
}
