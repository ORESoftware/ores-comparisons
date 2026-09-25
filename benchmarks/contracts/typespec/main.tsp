import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.Benchmarks;

@id("StackKind")
enum StackKind {
  beamscale: "beamscale",
  scintillaRun: "scintilla-run",
  oresStack: "ores-stack",
}

@id("ScenarioKind")
enum ScenarioKind {
  httpObservability: "http-observability",
  formsChatWorkflow: "forms-chat-workflow",
  cachedRpc: "cached-rpc",
}

@id("SourceKind")
enum SourceKind {
  local: "local",
  remote: "remote",
}

@id("SmokeStatus")
enum SmokeStatus {
  passed: "passed",
  failed: "failed",
  skipped: "skipped",
}

@id("DeploySmokeMode")
enum DeploySmokeMode {
  dryRun: "dry-run",
  artifactHandoff: "artifact-handoff",
}

@id("BenchmarkObservation")
model BenchmarkObservation {
  id: string;
  stack: StackKind;
  scenario: ScenarioKind;
  revision: string;
  source: SourceKind;
  measuredAt: string;
  samples: integer;
  httpStatus: integer;
  warmP50Micros: integer;
  warmP95Micros: integer;
  warmP99Micros: integer;
  coldStartMicros?: integer;
  memoryRssBytes?: integer;
  artifactSizeBytes?: integer;
  estimatedCostMicrousdPerMillion?: integer;
}

@id("SmokeReceipt")
model SmokeReceipt {
  id: string;
  stack: StackKind;
  scenario: ScenarioKind;
  revision: string;
  buildStatus: SmokeStatus;
  deployStatus: SmokeStatus;
  deployMode: DeploySmokeMode;
  note?: string;
}
