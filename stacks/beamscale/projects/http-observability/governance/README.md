# BeamScale / HTTP observability governance

This project binds to the shared `http-observability` peer-authority contract. Hosted Gleam actors keep the existing BeamScale capability boundary: local Postgres migration/seed is trusted orchestration outside tenant code; the worker does not receive ambient DB, filesystem, process, or secret authority.
