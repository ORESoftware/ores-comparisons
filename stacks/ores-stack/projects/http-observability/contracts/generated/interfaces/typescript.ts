// GENERATED. DO NOT EDIT.
export type Outcome = "ok" | "error";

export interface RequestObservation {
  id: string;
  requestId: string;
  route: string;
  statusCode: number;
  durationMs: number;
  outcome: Outcome;
  createdAt: string;
}
