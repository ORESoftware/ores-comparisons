# Form service governance

The comparison stacks share this exact contract pack. Schema changes require TypeSpec/JSON Schema convergence, preserved Protobuf numbers, an explicit storage mapping review, and an additive-first database migration. `tenant_id` is mandatory on every persisted entity; cross-tenant foreign keys are forbidden by application policy even when a local fixture uses one database.
