# LiteGraph runtime fixture orgs

These organizations are the GPU-specific runtime fixtures governed by `shared/dummy-org-fleet.json`.

| Org | Source | Target | Repositories |
| --- | --- | --- | ---: |
| `ores-dummy-org-rust-gpu-1` | `rust` | `gpu` | 19 |
| `ores-dummy-org-cuda-gpu-1` | `cuda` | `gpu` | 19 |

GPU fixtures are intentionally separated from the six general-purpose application scenario orgs. Topology materialization remains distinct from executable GPU/runtime proof.
