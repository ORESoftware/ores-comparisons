# Projection metadata governance

`contracts/projection.json` is code-generation input and is governed as
strictly as the domain model it projects.

The peer authorities are TypeSpec and JSON Schema Draft 2020-12. Neither is
generated from the other. Every live project projection is admitted against
this contract before SQL, Protobuf, or language interfaces are generated.

Cross-authority semantic checks still fail closed in the generator: model and
field existence, safe SQL identifiers, primary keys, enum storage type,
Protobuf message references, and seed/table consistency.
