import "@typespec/json-schema";
using TypeSpec.JsonSchema;

namespace OresComparisons.GitHubOrg;

@id("RepositoryKind")
enum RepositoryKind {
  governance: "governance",
  application: "application",
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
