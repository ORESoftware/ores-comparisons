import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.BigOrgExample;

@id("WorkKind")
enum WorkKind {
  create: "create",
  update: "update",
  sync: "sync",
}

@id("EnterpriseWorkItem")
model EnterpriseWorkItem {
  id: string;
  tenantId: string;
  actorId: string;
  kind: WorkKind;
  payload: string;
  createdAt: string;
}
