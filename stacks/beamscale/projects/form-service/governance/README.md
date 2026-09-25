# BeamScale form-service governance

This project binds to the shared `form-service` TypeSpec + JSON Schema authorities. BeamScale actor/lambda code may not acquire ambient database or secret authority; Postgres is a local comparison fixture and migrations run outside tenant actors before `bmscl dev` starts.
