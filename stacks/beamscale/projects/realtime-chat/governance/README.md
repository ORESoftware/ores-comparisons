# BeamScale realtime-chat governance

The actor runtime consumes the shared chat entity contract. Message ordering and tenant isolation are contract semantics, not BEAM implementation details. Local Postgres is migrated and seeded outside tenant actors before the BeamScale dev runtime starts.
