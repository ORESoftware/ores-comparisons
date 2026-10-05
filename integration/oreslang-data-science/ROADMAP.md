# Roadmap

The target is not a thin Python compatibility layer. The target is an Oreslang-native stack covering the common NumPy + pandas/Polars + SciPy + scikit-learn workflow plus high-throughput embedding generation and retrieval while exploiting Oreslang's runtime model.

## P0 — native numerical/data core

- [x] scalar math helpers;
- [x] stable mean/variance reductions;
- [x] dot, scale, Hadamard, AXPY, L2 normalization;
- [x] row-major dense matrix with destination-buffer matvec;
- [x] typed nullable float columns;
- [x] eager map/filter buffer kernels;
- [x] Struct-of-Arrays point/particle examples;
- [x] native Oreslang test suite using the core test framework;
- [x] row-major embedding batches with model/revision identity;
- [x] batch dot/cosine/squared-L2 kernels with caller-owned output buffers;
- [x] row-wise top-k plus fused dot + top-k retrieval;
- [ ] benchmark harness against equivalent NumPy/Polars/vector operations.

## P1 — contiguous arrays, embeddings, and ndarray semantics

- dedicated contiguous typed `Array<T>`;
- aligned `f32`, `f16`, and `bf16` numeric buffers;
- n-dimensional arrays with shape/stride metadata;
- zero-copy views and slicing;
- broadcasting;
- migrate embedding storage from `List<float>` to contiguous arrays without changing the public shape;
- batch-first tokenizer/model input buffers;
- pooling + row normalization;
- bit-packed validity masks;
- heterogeneous DataFrame schema;
- select/filter/with_column/group_by/aggregate/join/sort;
- lazy logical plans and expression trees;
- chunked execution and operator fusion.

## P2 — embedding model runtime and data interchange

- actor-owned resident embedding-model sessions;
- request microbatching with Future/Awaitable completion;
- immediate cancellation of queued embedding requests;
- backend cancellation where the inference engine supports it;
- pluggable model execution backends;
- fused normalized embedding output;
- CSV and JSON readers/writers;
- Arrow-compatible column buffers;
- Parquet;
- memory mapping;
- streaming readers with backpressure;
- zero-copy handoff where ownership rules permit it.

## P3 — statistics and scientific computing

- quantiles, covariance, correlation;
- histograms;
- distributions and random sampling;
- linear algebra decompositions;
- numerical integration and root finding;
- time-series windows/rolling operations.

## P4 — machine learning and retrieval

- train/test split;
- preprocessing pipelines;
- linear/logistic regression;
- trees and ensembles;
- clustering;
- metrics;
- model serialization;
- tensor/accelerator bridge;
- approximate-nearest-neighbor adapters;
- quantized embedding storage/search;
- vector-database interchange.

## P5 — runtime/compiler acceleration

- recognize reduction, embedding, and linear-algebra kernels;
- loop fusion;
- SIMD;
- multicore chunk scheduling;
- effect-aware parallel plans;
- fused pooling + normalization;
- fused dot + top-k;
- AOT specialization for common embedding dimensions;
- Regent/Legion-inspired CPU/GPU mapping;
- explicit transfer planning for GPU-resident arrays and columns;
- cache-aware batch sizing and microbatching;
- AOT specialization for known shapes and schemas.

## Embedding performance bar

Track vectors/second for normalize, dot, cosine, squared-L2, and top-k; texts/second and tokens/second for model inference; p50/p95 end-to-end latency; allocations and bytes copied per request; host/device transfer volume; CPU/GPU utilization; achieved batch occupancy; and warm inference separately from cold model startup.

## Competitive bar

For common exploratory and embedding workloads, Oreslang should aim for:

- Python-level ergonomics without a Python interpreter dependency;
- Polars-style columnar execution;
- NumPy-class dense numeric kernels;
- batch-oriented vector retrieval without boxed vector objects;
- Rust-like predictable native deployment;
- Oreslang-native actor/task isolation and heterogeneous scheduling.
