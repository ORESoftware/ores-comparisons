import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.RuntimeTopology;

@id("RuntimeRole")
enum RuntimeRole {
  service: "service",
  worker: "worker",
  frontend: "frontend",
  application: "application",
}

@id("RuntimeService")
model RuntimeService {
  name: string;
  repository: string;
  role: RuntimeRole;
  dependsOn: string[];
  localBind?: string;
  healthUrl?: string;
}

@id("RuntimeTopology")
model RuntimeTopology {
  schema: string;
  projectId: string;
  stack: string;
  scenario: string;
  services: RuntimeService[];
}
