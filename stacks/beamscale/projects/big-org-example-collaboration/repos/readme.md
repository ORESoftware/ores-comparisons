# Local GitHub organization mirror

This directory intentionally emulates the root of a GitHub organization.

Allowed top-level entries here are:
- `.github/` — the simulated organization `.github` repository containing shared contracts, governance, conformance, environment policy, local orchestration, and org profile material.
- one or more sibling application/service repositories such as `app/`.
- this `readme.md`, which explains the local organization mirror.

Do not place shared project code or configuration directly in `repos/`; put org-shared material in `repos/.github/` and repository-specific material in its sibling repository directory.
