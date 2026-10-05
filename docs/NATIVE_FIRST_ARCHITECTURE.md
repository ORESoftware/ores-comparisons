# Native-first Oreslang architecture

Oreslang is implemented on GraalVM/Truffle, but Java is the host kernel, not the
default standard-library language.

## Rule

Prefer Oreslang source for behavior that can be expressed safely in Oreslang.
Use Java only where the implementation must cross the Truffle/compiler/runtime
boundary. Use JNI/native code only where the runtime must cross the JVM/native
OS or device boundary.

The intended direction is:

```text
application .ores
    -> std/*.ores                  language/library behavior
    -> reserved runtime intrinsic  narrow capability/scheduler primitive
    -> Java Truffle host kernel    lowering, scheduling, isolation, handles
    -> JNI/native                  OS/device/driver primitive when required
```

A Java implementation is not justified merely because Java already has a
library for the feature.

## Java host-kernel responsibilities

Java may own:

- lexer/parser/AST/type/ownership/effect checking and Truffle nodes;
- code-unit linking, AOT/JIT integration and source-generation lifecycle;
- scheduler queues, continuation settlement, GC/isolate bookkeeping;
- capability admission and opaque generation-safe native handles;
- Graal/Polyglot interop boundaries;
- JNI/native bridge lifecycle and conversion at the boundary;
- minimal intrinsics whose semantics cannot be expressed in guest code without
  circular bootstrap dependencies.

Java should not own, by default:

- HTTP or other wire-protocol policy;
- collection algorithms and ordinary transforms;
- Option/Result/Iterator/Stream combinator policy;
- reactive operator graphs;
- matrix/vector API policy when a small numeric kernel primitive suffices;
- actor supervision policy that can be written against runtime primitives;
- URI parsing, retry policy, backoff, redirect semantics, framing rules;
- application-level serialization or protocol state machines.

## Reserved `std/` namespace

`std/` is compiler-owned, read-only and bundled with the Oreslang toolchain.
An import such as:

```ores
import module http_wire from "std/net/http";
```

resolves to a bundled `.ores` code unit. User source cannot shadow
`std/*`, and traversal outside the reserved namespace fails closed.

Standard-library units participate in the normal import graph, ABI hashing,
initialization ordering, tree shaking and AOT reachability. They are not
reflective plugins and do not receive ambient host access.

## Native/JNI boundary

JNI is acceptable when it is the narrowest honest boundary, for example:

- sockets, epoll/kqueue/IOCP and file descriptors/handles;
- thread creation/affinity where the runtime needs OS control;
- BLAS/LAPACK/SIMD kernels;
- GPU drivers and device queues;
- clocks, signals and platform-specific process primitives.

The JNI surface should expose opaque handles and data-oriented buffers, not
high-level Java policy objects. Guest code never receives raw pointers.

## Migration audit

Current/recent draft work should converge as follows:

| Area | Keep in Java/native | Move/keep in Oreslang |
| --- | --- | --- |
| Future/await | settlement, scheduler enqueue, cancellation primitive | map/flat_map policy and convenience composition |
| rx-ores | single-pull/cancel primitive if needed | sources, operators, take/first/merge/filter/etc. |
| networking | capability checks, opaque socket/TLS handles, readiness | HTTP/URI/framing/redirect/retry policy |
| threads | JNI thread handle, interrupt/wait primitive | public convenience API and orchestration |
| math | primitive/vectorized/BLAS/GPU kernels | Matrix/Vector surface and non-kernel algorithms |
| GPU | device discovery, buffers, launch/fence primitive | mapping policy expressible through compute/effect contracts |
| actors | queues, leases, continuation wakeup, isolation enforcement | supervision/library protocols and reusable actor behaviors |

## Review gate

For every new Java runtime feature, reviewers should ask:

1. Is this a compiler/Truffle/scheduler/capability/native primitive?
2. Could the same semantics be implemented in a `.ores` standard-library unit?
3. If Java is required, is the exposed primitive smaller than the user-facing
   feature?
4. If JNI is required, are raw addresses/FDs/device handles hidden behind opaque
   generation-safe handles?
5. Does the `std/*` layer remain testable under JIT, AOT and hybrid modes?

If (2) is yes, implement the feature in Oreslang first.
