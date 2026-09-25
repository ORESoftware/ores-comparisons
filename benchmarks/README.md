# Benchmarks and smoke tests

This folder holds the typed benchmark/smoke authority and the nine-project comparison matrix.

BeamScale and Scintilla use their native deploy dry-run paths. ORES Stack application
examples stop at build/artifact handoff because deployment targets belong in an infra repo;
the harness records that distinction instead of reporting a fictional deploy success.

The runners live under `scripts/` and emit receipts under `benchmarks/results/`.
