# CI evidence-scope receipts

`verify-comparisons` must not be interpreted as full runtime proof solely
because the GitHub Actions check is green. Public structural checks, initialized
project content, exact private contract authorities, complete private source
enumeration, and the separate 18-project execution proof are independent domains.

## Current GitHub Actions behavior

`.github/workflows/verify.yml` now runs `evidence-scope` after both
`static-contracts` and `private-authorities` and uploads
`ci-evidence-scope.json`, tied to `github.sha`. It always runs,
including when one of the preceding jobs has failed.

The report records `passed`, `blocked`, `failed`, and `not_run` for:

- Public static/structural checks;
- Public project-content checks conditional on the absence of `.gitmodules`;
- Private cross-repository contract and smoke checks;
- Remote source-project enumeration;
- `runtime-proof-set-18` (always `not_run` in this workflow).

Private credential availability is passed via GitHub job outputs containing
only `yes`/`no`. The downstream evidence job receives **no tokens**.
Missing job outputs are treated as unknown, not proof. Credential absence
is `blocked` even when a skipped execution step exits with code zero.

The independent `runtime-proof-set-18` certificate still has its own
workflow; this CI-scope report **does not import that evidence** and therefore
never claims full runtime verification from the ordinary verify workflow.

A structural job may remain green when private access is unavailable. The
report will still read `coverage_status: incomplete`. Consumers that
require full verification must check the semantic status, not the Actions
workflow color. To fail closed in a release/consumer gate use:

```sh
python3 scripts/ci_evidence_receipt.py \
  --static-result success --private-result success \
  --private-auth no --source-auth no \
  --revision "$(git rev-parse HEAD)" \
  --repository ORESoftware/ores-comparisons \
  --require-complete
```

That command intentionally exits nonzero until all required evidence domains
are independently proven. This is not a credential workaround and does not
silently replace the private authority checks with snapshots.

The receipt generator is covered by 11 mutation/unit checks in
`tests/test_ci_evidence_receipt.py`.
