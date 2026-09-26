# Adversarial fleet fixtures

These fixtures are deliberately invalid, synthetic, and non-production. They give fleet auditors a stable corpus of failures that **must** be detected before compatibility or certification can be claimed.

The corpus currently plants defects for legacy/mutable ORES Stack CLI pins, PATH shadowing, server/deployment contract digest mismatch, ambiguous compose discovery, unsupported provider capabilities, mutable deployment artifacts, zero-step evidence falsely labeled passed, and source-digest mismatch.

Run:

```sh
python3 scripts/verify_adversarial_fixtures.py
python3 -m unittest tests.test_adversarial_fixtures
```

The verifier is intentionally read-only and checks that each case produces exactly its declared deterministic finding IDs. Adding a new fixture requires adding detection logic and a reviewed expected finding; simply writing an `expected_findings` array is not itself proof.
