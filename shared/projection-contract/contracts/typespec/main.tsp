import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.Projection;

@id("ProjectionSchema")
enum ProjectionSchema {
  v1: "ores.comparisons.projection/v1",
}

@id("ProjectionScenario")
enum ProjectionScenario {
  httpObservability: "http-observability",
  formsChatWorkflow: "forms-chat-workflow",
  cachedRpc: "cached-rpc",
}

@id("SqlType")
enum SqlType {
  text: "TEXT",
  integer: "INTEGER",
  timestamptz: "TIMESTAMPTZ",
  jsonb: "JSONB",
}

@id("ColumnProjection")
model ColumnProjection {
  name: string;
  source: string;
  sql_type: SqlType;
  nullable?: boolean;
}

@id("TableProjection")
model TableProjection {
  name: string;
  model: string;
  primary_key: string;
  columns: ColumnProjection[];
}

@id("SeedProjection")
model SeedProjection {
  table: string;
  rows: unknown[];
}

@id("ProtoFieldProjection")
model ProtoFieldProjection {
  type: string;
  name: string;
  number: integer;
}

@id("ProtoMessageProjection")
model ProtoMessageProjection {
  name: string;
  fields: ProtoFieldProjection[];
}

@id("ProtoRpcProjection")
model ProtoRpcProjection {
  name: string;
  request: string;
  response: string;
}

@id("ProtoProjection")
model ProtoProjection {
  package: string;
  service: string;
  messages: ProtoMessageProjection[];
  rpcs: ProtoRpcProjection[];
}

@id("InterfaceProjection")
model InterfaceProjection {
  model: string;
}

@id("ProjectionContract")
model ProjectionContract {
  schema: ProjectionSchema;
  scenario: ProjectionScenario;
  tables: TableProjection[];
  seed: SeedProjection[];
  proto: ProtoProjection;
  interfaces: InterfaceProjection[];
}
