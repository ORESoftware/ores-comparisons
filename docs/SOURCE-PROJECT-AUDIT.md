# Native source project inventory and stack-ownership audit

**Scope (2026-10-08):** Native source repositories owned by the
[ORES Stack](https://github.com/ores-stack) and
[LiteGraph](https://github.com/litegraph) organizations. This is separate
from the dummy-org fixture repos, the 18-project executable matrix, and
any CI evidence about tenant isolation, deployment, or benchmark results.

The machine-readable authority is [shared/source-project-inventory.json](../shared/source-project-inventory.json).
It registers **20 ORES Stack** and **29 LiteGraph** actual source repositories,
including deployment, interface, application, runtime, and tooling repos.
All 49 were discovered as private repos with a main default branch in the
October 8, 2026 GitHub owner census. They are deliberately **not** added
as additional 20-repo dummy-org fixtures or runtime benchmark successes.

## Why this is a separate ledger

The materialized fixture families in shared/dummy-org-fleet.json exercise
synthetic source/target combinations. A fixture named
ores-dummy-org-rust-gpu-1-api-server.rs does **not** mean that
litegraph/litegraph-api-server.rs or litegraph/litegraph-gpu-host has been
integrated or certified in the comparison runtime matrix.

Similarly, the ORES Stack fixture server roles are not a comprehensive
inventory of the framework's native libraries, CLI, clients, infra, or SDK
source projects. A source project is registered with category metadata only;
it gains build, contract, or runtime proof through separately reviewed
executable evidence.

## Verification

Offline on every PR:

    python3 scripts/verify_source_project_inventory.py
    python3 -m unittest tests.test_source_project_inventory

This verifies that both source owners exist in the canonical stack catalog,
each repository has exactly one stack owner, naming/branch/visibility/category
metadata is well formed, repository identities are unique and sorted, and
the initial project census floor cannot silently shrink. An offline green
status is *not* evidence that no new private repositories exist upstream.

For a complete live comparison, run with a dedicated read-only GitHub
credential that can list **all private repositories** in both organizations:

    export SOURCE_PROJECT_AUDIT_TOKEN=...
    python3 scripts/verify_source_project_inventory.py --remote

Remote mode enumerates all repository pages for each owner and fails on:

- Newly discovered projects absent from the stack registry (reported as
  **UNASSIGNED SOURCE PROJECT**);
- Recorded projects no longer visible or present (reported as
  **MISSING REMOTE PROJECT**, including insufficient access);
- A repository whose default branch or visibility differs from the ledger;
- An incomplete API census, API error, or absent audit credential.

Do not treat an unavailable credential, partial public-only API listing,
or a skipped remote audit as successful upstream discovery. GitHub Actions
runs the offline gate unconditionally and runs this remote gate only when
SOURCE_PROJECT_AUDIT_TOKEN is available, with an explicit notice otherwise.

## Unassigned vs shared vs pending

**Within the two scoped native owner organizations, no orphaned project was
observed in the October 8 census:** all 49 were assigned to their matching
catalog stack. The remote verifier will block any newly discovered unmatched
project instead of guessing its ownership.

**Stack-neutral integrations are not orphans.** For example,
ORESoftware/api-docs, ORESoftware/ores-compose, and
ORESoftware/typespec-json-schema-validator provide shared comparison
authority, while services from shared/integrations.json are reusable
cross-stack dependencies. Do not force these into LiteGraph or ORES Stack
merely to make every repository fit a single runtime.

**Oreslang Stack** is separately registered in the catalog with pending
topology and portability proof. Its lack of native fixture gitlinks is
intentional and must not be quietly counted as a missing *materialized*
fixture. See docs/ORESLANG-STACK.md.

## Outstanding validation

- Native source records are a declaration; no native source SHA or workflow
  run is attested here. To add executable comparison coverage, pin source
  commits and separately prove build/deploy/smoke/benchmark semantics.
- A full inventory of *all other* GitHub organizations and independent
  ORESoftware libraries is outside this two-stack census. Any repository
  beyond the two owners needs explicit ownership or a stack-neutral
  classification before being claimed as comparison coverage.
- LiteGraph has no organization-level .github repository in this census
  (unlike ORES Stack); assess whether org-wide workflow/security policy
  belongs in a new shared .github repo, but do not invent or provision it
  as part of a fixture audit.
