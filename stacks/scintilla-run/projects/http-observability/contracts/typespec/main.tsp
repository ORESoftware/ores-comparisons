import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.HttpObservability;

@id("Outcome")
enum Outcome {
  ok: "ok",
  error: "error",
}

@id("RequestObservation")
model RequestObservation {
  id: string;
  requestId: string;
  route: string;
  statusCode: integer;
  durationMs: integer;
  outcome: Outcome;
  createdAt: string;
}
