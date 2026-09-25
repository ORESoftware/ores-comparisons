# Local database

Ores Compose starts Postgres; startup admits TypeSpec + JSON Schema, generates/converges SQL, migrates `workflow_runs`, `form_responses`, and `workflow_messages`, seeds deterministic fixtures, then starts `bmscl dev`.
