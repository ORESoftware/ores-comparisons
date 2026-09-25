import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.GitHubOrg;

@id("RepositoryKind")
enum RepositoryKind {
  governance: "governance",
  application: "application",
  service: "service",
  worker: "worker",
  frontend: "frontend",
  generatedSdk: "generated-sdk",
  contractTests: "contract-tests",
}

@id("RepositoryRole")
model RepositoryRole {
  name: string;
  path: string;
  kind: RepositoryKind;
  dependsOn: string[];
  generatedFrom?: string;
}

@id("OrganizationManifest")
model OrganizationManifest {
  schema: string;
  organization: string;
  stack: string;
  scenario: string;
  repositories: RepositoryRole[];
}

@id("RepositoryContract")
model RepositoryContract {
  schema: string;
  name: string;
  stack: string;
  scenario: string;
  kind: RepositoryKind;
  contractAuthority: string;
  generatedSdk: string;
  governanceRepo: string;
  healthPath: string;
  logicalRoute: string;
}
