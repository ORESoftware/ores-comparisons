# Local database

`.ores-compose.yaml` starts local Postgres first. App startup then runs full `tjsv` admission, generates both SQL lanes, requires convergence, applies `generated/sql/001_schema.sql`, seeds the shared HTTP-observability entities, and only then starts `bmscl dev`.
