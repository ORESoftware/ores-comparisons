# Database

The local compose cluster starts Postgres, generates the shared chat SQL projection, migrates `conversations` and `chat_messages`, seeds a comparison room/message, then starts `bmscl dev`.
