import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.HttpObservability;

@id("HttpOutcome")
enum HttpOutcome {
  ok: "ok",
  client_error: "client_error",
  server_error: "server_error",
}

@id("HttpObservation")
model HttpObservation {
  id: string;
  tenant_id: string;
  request_id: string;
  method: string;
  path: string;
  status_code: int32;
  duration_ms: int32;
  outcome: HttpOutcome;
  created_at: string;
}

@id("TelemetrySpan")
model TelemetrySpan {
  id: string;
  tenant_id: string;
  http_observation_id: string;
  trace_id: string;
  span_name: string;
  duration_ms: int32;
  created_at: string;
}

@id("RecordObservationRequest")
model RecordObservationRequest {
  tenant_id: string;
  request_id: string;
  method: string;
  path: string;
  status_code: int32;
  duration_ms: int32;
}

@id("RecordObservationResponse")
model RecordObservationResponse {
  observation: HttpObservation;
}

interface HttpObservabilityService {
  op record_observation(request: RecordObservationRequest): RecordObservationResponse;
}
