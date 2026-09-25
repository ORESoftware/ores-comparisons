# Forms/chat workflow governance

The form, workflow, and chat state machine is shared across all runtime stacks. Persisted rows always carry `tenant_id`; child records must remain in the parent workflow's tenant. Workflow `revision` and message `sequence` are monotonic application contracts. TypeSpec and JSON Schema are peer authorities; transport/runtime adapters cannot redefine the workflow state machine.
