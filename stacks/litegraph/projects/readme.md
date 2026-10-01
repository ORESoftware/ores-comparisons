# LiteGraph comparison projects

LiteGraph uses dedicated accelerator fixtures and deliberately separates CPU-hosted invocation actors from GPU kernels/artifacts.

| Fixture org | Source → target | Canary | Current proof |
| --- | --- | --- | --- |
| `ores-dummy-org-rust-gpu-1` | Rust → GPU | portable `vector_add` semantics | source-contract only |
| `ores-dummy-org-cuda-gpu-1` | CUDA → NVIDIA GPU | real `sm_80` `vector_add` kernel | `nvcc` cubin build contract; hardware proof required |

The Rust lane is intentionally not called a GPU artifact yet: `litegraph-compiler` currently validates/normalizes multi-target plans but its compile path is still a mock planner, and no reviewed Rust→SPIR-V/CUDA/ROCm/Metal adapter exists.

The CUDA lane builds `dist/vector_add.cubin` with `nvcc -arch=sm_80`, but LiteGraph's own policy requires real compatible hardware evidence before a CUDA claim counts as runtime proof. The CUDA toolchain image must also be pinned before reproducible promotion.

LiteGraph therefore remains `status: registered`. Both fixture lanes must become `runtime_proven`, with a real `litegraph-gpu-host` hardware receipt for GPU execution, before promotion.
