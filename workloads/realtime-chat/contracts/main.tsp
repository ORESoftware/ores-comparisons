import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.RealtimeChat;

@id("MessageKind")
enum MessageKind {
  text: "text",
  image: "image",
  system: "system",
}

@id("Conversation")
model Conversation {
  id: string;
  tenant_id: string;
  topic: string;
  created_at: string;
}

@id("ChatMessage")
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

@id("PublishMessageRequest")
model PublishMessageRequest {
  tenant_id: string;
  conversation_id: string;
  sender_id: string;
  kind: MessageKind;
  body: string;
}

@id("PublishMessageResponse")
model PublishMessageResponse {
  message: ChatMessage;
}

interface ChatService {
  op publish_message(request: PublishMessageRequest): PublishMessageResponse;
}
