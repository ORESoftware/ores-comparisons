// GENERATED. DO NOT EDIT.
export type WorkKind = "create" | "update" | "sync";

export interface EnterpriseWorkItem {
  id: string;
  tenantId: string;
  actorId: string;
  kind: WorkKind;
  payload: string;
  createdAt: string;
}
