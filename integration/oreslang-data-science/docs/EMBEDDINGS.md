# Embeddings and vector retrieval

## Goal

Embedding generation and retrieval are first-class data-oriented Oreslang workloads. The public surface is batch-first, row-major, allocation-conscious, and designed so the semantic Oreslang implementation can later lower to SIMD, multicore, or GPU kernels without changing caller code.

## Storage contract

`EmbeddingBatch` stores the row count, embedding dimension, one flat row-major numeric buffer, normalization state, model identifier, and model revision.

Current core `main` uses `List<float>` as the semantic storage primitive. The intended hot representation is contiguous `Array<f32>` with known alignment and shape metadata:

```text
[row0_dim0, row0_dim1, ... row0_dimD,
 row1_dim0, row1_dim1, ... row1_dimD,
 ...]
```

This keeps dense retrieval compatible with BLAS/GEMM-style execution and avoids object-per-vector layouts.

## Batch-first kernels

The initial native surface includes:

- `mean_pool_token_embeddings_into` for mask-aware transformer hidden-state pooling;
- `normalize_embedding_rows_into`;
- `dot_similarity_into`;
- `cosine_similarity_into`;
- `squared_l2_distance_into`;
- `top_k_indices_into`;
- `top_k_dot_into`.

All hot kernels accept caller-owned destination buffers. `top_k_dot_into` is particularly important for retrieval because it computes and ranks scores without allocating the full `[queries, corpus]` score matrix.

For normalized embeddings, cosine ranking reduces to dot-product ranking. Applications should normalize once after model pooling and use dot/top-k for repeated retrieval.

## Model-space identity

Dimensions alone do not make two embeddings comparable. Batches carry non-empty `model_id` and `model_revision` values. Similarity and fused-retrieval kernels reject cross-model or cross-revision comparisons before mutating destination buffers, even when dimensions happen to match. Higher-level services may impose additional policy, but the low-level library does not silently compare incompatible embedding spaces.

## Backend contract

`src/ml/model.ores` defines `EmbeddingBackend` as the low-level model execution boundary. It exposes model id/revision, output dimensions, maximum batch size, and one batch inference operation that fills caller-owned `rows * dimensions` storage.

The interface is intentionally backend-neutral: ONNX, GGUF/native, device-specific, remote, or a future Oreslang tensor backend can implement the same contract. It is also intentionally synchronous at this layer. Actor ownership, request coalescing, deadlines, quotas, and Future/Awaitable completion belong in the runtime service wrapper rather than inside every inference engine.

## Embedding generation pipeline

`TokenEmbeddingBatch` represents padded transformer hidden states as one flat `[batch, tokens, hidden]` buffer plus a separate boolean attention mask. Mean pooling writes directly into the caller-owned `[batch, hidden]` embedding buffer; optional normalization is fused into that operation.

The target runtime pipeline is:

```text
text
  -> tokenizer
  -> microbatch builder
  -> model inference
  -> pooling
  -> row normalization
  -> EmbeddingBatch
  -> dot / top-k / vector database
```

The model/session should be actor-owned. Concurrent callers send requests to a resident embedding worker; the worker may coalesce mailbox requests into a microbatch and resolve per-request Futures after inference. This keeps model state isolated and makes batching a runtime optimization rather than a burden on every caller.

Cancellation must remove queued requests immediately. Backend/device cancellation should be used when available for a running batch whose work is no longer needed.

## Compiler/runtime lowering

The pure Oreslang kernels are the semantic reference. Optimized compilation may recognize stable kernel shapes and select scalar reference code, vectorized CPU code, cache-aware multicore chunks, or accelerator/device kernels.

Important specialization dimensions include dtype, embedding dimension, row count/batch size, normalization state, and residency. Common dimensions such as 384, 512, 768, 1024, 1536, 2048, 3072, and 4096 are good AOT specialization targets without restricting arbitrary dimensions.

The contiguous-array runtime should eventually support:

- aligned `f32`, `f16`, and `bf16` storage;
- zero-copy slices/views;
- explicit host/device residency;
- pinned transfer buffers where useful;
- caller-owned reusable destination buffers;
- fused mask-aware mean pooling + normalization;
- fused dot + top-k;
- quantized storage/search paths such as int8 where error budgets allow it.

## Benchmark gates

Embedding work should be measured using vectors/second for dot, cosine, normalize, and top-k; texts/second and tokens/second for model inference; p50/p95 latency; allocations per request; bytes copied; host/device transfer bytes; CPU/GPU utilization; and achieved batch occupancy.

Warm inference and cold model startup are separate benchmarks.
