package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure planning contract for Oreslang data-oriented CPU/GPU execution.
 *
 * <p>The planner deliberately separates logical correctness from physical
 * placement. Region identity, certified partition disjointness, field effects,
 * and reduction/atomic rules are checked before a CPU/GPU variant is selected.
 * No guest-visible native pointer, device handle, or mapper authority is
 * exposed by this API.</p>
 */
public final class HeterogeneousTaskMapper {
    public enum ProcessorKind { CPU, GPU }
    public enum PlacementHint { AUTO, CPU, GPU }
    public enum Privilege { READ, WRITE, DISCARD, REDUCE, ATOMIC }
    public enum LayoutKind { AUTO, AOS, SOA, AOSOA, TILED }

    /**
     * Physical layout is an optimization contract, not part of logical Region<T>
     * identity. AUTO leaves layout selection to the backend.
     */
    public record Layout(LayoutKind kind, int blockSize, List<Integer> tileShape) {
        public Layout {
            Objects.requireNonNull(kind, "kind");
            tileShape = List.copyOf(Objects.requireNonNull(tileShape, "tileShape"));
            for (Integer extent : tileShape) {
                if (extent == null || extent <= 0) {
                    throw new IllegalArgumentException("tile extents must be positive");
                }
            }
            switch (kind) {
                case AUTO, AOS, SOA -> {
                    if (blockSize != 0 || !tileShape.isEmpty()) {
                        throw new IllegalArgumentException(kind + " layout takes no block/tile parameters");
                    }
                }
                case AOSOA -> {
                    if (blockSize <= 0 || !tileShape.isEmpty()) {
                        throw new IllegalArgumentException("AOSOA requires a positive block size and no tile shape");
                    }
                }
                case TILED -> {
                    if (blockSize != 0 || tileShape.isEmpty()) {
                        throw new IllegalArgumentException("TILED requires a non-empty tile shape and no block size");
                    }
                }
            }
        }

        public static Layout auto() { return new Layout(LayoutKind.AUTO, 0, List.of()); }
        public static Layout aos() { return new Layout(LayoutKind.AOS, 0, List.of()); }
        public static Layout soa() { return new Layout(LayoutKind.SOA, 0, List.of()); }
        public static Layout aosoa(int blockSize) { return new Layout(LayoutKind.AOSOA, blockSize, List.of()); }
        public static Layout tiled(int... extents) {
            ArrayList<Integer> shape = new ArrayList<>(extents.length);
            for (int extent : extents) shape.add(extent);
            return new Layout(LayoutKind.TILED, 0, shape);
        }
    }

    /** Opaque logical-region identity minted only by trusted runtime code. */
    public static final class RegionIdentity {
        private RegionIdentity() { }
    }

    /** Opaque proof that sibling slices are members of one certified disjoint partition. */
    static final class DisjointFamily {
        private DisjointFamily() { }
    }

    static RegionIdentity allocateRegionIdentity() {
        return new RegionIdentity();
    }

    static DisjointFamily certifyDisjointFamily() {
        return new DisjointFamily();
    }

    /**
     * Logical region/slice identity. Construction is private so arbitrary code
     * cannot attach a forged root identity or disjointness proof.
     */
    public static final class RegionSlice {
        private static final long ROOT_SLICE_ID = 1L;

        private final RegionIdentity root;
        private final long sliceId;
        private final DisjointFamily disjointFamily;

        private RegionSlice(RegionIdentity root, long sliceId, DisjointFamily disjointFamily) {
            this.root = Objects.requireNonNull(root, "root");
            if (sliceId <= 0) throw new IllegalArgumentException("sliceId must be positive");
            this.sliceId = sliceId;
            this.disjointFamily = disjointFamily;
        }

        static RegionSlice root(RegionIdentity root) {
            return new RegionSlice(root, ROOT_SLICE_ID, null);
        }

        static RegionSlice certified(RegionIdentity root, long sliceId, DisjointFamily family) {
            if (sliceId == ROOT_SLICE_ID) {
                throw new IllegalArgumentException(
                        "certified partition slices cannot reuse the reserved root slice id");
            }
            return new RegionSlice(root, sliceId, Objects.requireNonNull(family, "family"));
        }

        private RegionIdentity rootIdentity() {
            return root;
        }

        public boolean mayAlias(RegionSlice other) {
            Objects.requireNonNull(other, "other");
            if (root != other.root) return false;
            if (sliceId == other.sliceId) return true;
            return disjointFamily == null
                    || other.disjointFamily == null
                    || disjointFamily != other.disjointFamily;
        }
    }

    /**
     * Zero-copy logical projection. Empty fields means the whole region.
     * Layout remains a physical preference/requirement and never changes
     * logical aliasing.
     */
    public record RegionView(RegionSlice slice, Set<String> fields, Layout layout) {
        public RegionView {
            Objects.requireNonNull(slice, "slice");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(layout, "layout");
            fields = validateFieldPaths(fields, "region-view");
        }

        public static RegionView whole(RegionSlice slice) {
            return new RegionView(slice, Set.of(), Layout.auto());
        }

        public RegionView fields(String... selected) {
            return new RegionView(slice, Set.of(selected), layout);
        }

        public RegionView withLayout(Layout nextLayout) {
            return new RegionView(slice, fields, nextLayout);
        }
    }

    /**
     * Logical effect footprint. READ/WRITE/DISCARD/REDUCE/ATOMIC are semantic
     * privileges; physical layout and processor placement cannot weaken them.
     */
    public record Access(
            RegionSlice slice,
            Set<String> fields,
            Privilege privilege,
            String reductionOperator) {
        public Access {
            Objects.requireNonNull(slice, "slice");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(privilege, "privilege");
            fields = validateFieldPaths(fields, "task access");
            if (privilege == Privilege.REDUCE) {
                if (reductionOperator == null || reductionOperator.isBlank()) {
                    throw new IllegalArgumentException("reduction access requires a named reduction operator");
                }
            } else if (reductionOperator != null) {
                throw new IllegalArgumentException("only reduction accesses may name a reduction operator");
            }
        }

        public static Access read(RegionSlice slice) {
            return new Access(slice, Set.of(), Privilege.READ, null);
        }

        public static Access readFields(RegionSlice slice, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.READ, null);
        }

        public static Access write(RegionSlice slice) {
            return new Access(slice, Set.of(), Privilege.WRITE, null);
        }

        public static Access writeFields(RegionSlice slice, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.WRITE, null);
        }

        public static Access discard(RegionSlice slice) {
            return new Access(slice, Set.of(), Privilege.DISCARD, null);
        }

        public static Access discardFields(RegionSlice slice, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.DISCARD, null);
        }

        public static Access reduce(RegionSlice slice, String operator) {
            return new Access(slice, Set.of(), Privilege.REDUCE, operator);
        }

        public static Access reduceFields(RegionSlice slice, String operator, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.REDUCE, operator);
        }

        public static Access atomic(RegionSlice slice) {
            return new Access(slice, Set.of(), Privilege.ATOMIC, null);
        }

        public static Access atomicFields(RegionSlice slice, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.ATOMIC, null);
        }

        public static Access fromView(RegionView view, Privilege privilege) {
            return new Access(view.slice(), view.fields(), privilege, null);
        }

        public boolean mayOverlapFields(Access other) {
            Objects.requireNonNull(other, "other");
            if (fields.isEmpty() || other.fields.isEmpty()) return true;
            for (String left : fields) {
                for (String right : other.fields) {
                    if (fieldPathsOverlap(left, right)) return true;
                }
            }
            return false;
        }
    }

    /**
     * One backend implementation of one logical compute task. Variants share the
     * same TaskSpec effects and differ only in physical execution requirements.
     */
    public record Variant(
            String name,
            ProcessorKind processor,
            long minimumWorkItems,
            boolean parallel,
            boolean simd,
            Layout requiredLayout) {
        public Variant {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("variant name cannot be blank");
            Objects.requireNonNull(processor, "processor");
            Objects.requireNonNull(requiredLayout, "requiredLayout");
            if (minimumWorkItems < 0) throw new IllegalArgumentException("minimumWorkItems cannot be negative");
            if (processor == ProcessorKind.GPU && simd) {
                throw new IllegalArgumentException("SIMD is a CPU variant property; GPU lanes are backend-defined");
            }
        }

        public Variant(String name, ProcessorKind processor, long minimumWorkItems) {
            this(name, processor, minimumWorkItems, false, false, Layout.auto());
        }
    }

    public record TaskSpec(String name, List<Access> accesses, List<Variant> variants) {
        public TaskSpec {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("task name cannot be blank");
            accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses"));
            variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
            if (variants.isEmpty()) throw new IllegalArgumentException("task requires at least one processor variant");
            Set<String> variantNames = new LinkedHashSet<>();
            for (Variant variant : variants) {
                if (!variantNames.add(variant.name())) {
                    throw new IllegalArgumentException("duplicate task variant name: " + variant.name());
                }
            }
        }
    }

    /**
     * gpuCrossoverWorkItems is a heuristic, not a correctness rule. Each
     * non-resident logical root increases the AUTO crossover threshold so
     * transfer costs are conservatively represented.
     */
    public record Machine(boolean gpuAvailable, long gpuCrossoverWorkItems, long gpuMemoryBudgetBytes) {
        public Machine {
            if (gpuCrossoverWorkItems < 0) {
                throw new IllegalArgumentException("gpuCrossoverWorkItems cannot be negative");
            }
            if (gpuMemoryBudgetBytes < 0) {
                throw new IllegalArgumentException("gpuMemoryBudgetBytes cannot be negative");
            }
        }

        public Machine(boolean gpuAvailable, long gpuCrossoverWorkItems) {
            this(gpuAvailable, gpuCrossoverWorkItems, Long.MAX_VALUE);
        }
    }

    public record Mapping(Variant variant, ProcessorKind processor) {
        public Mapping {
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(processor, "processor");
            if (variant.processor() != processor) {
                throw new IllegalArgumentException("variant/processor mismatch");
            }
        }
    }

    /**
     * CPU work decomposition for dense region/archetype traversal. A chunk is a
     * scheduler unit, not an entity. SIMD width rounds chunk boundaries so SoA
     * and AoSoA backends can keep vector lanes full except for the final tail.
     */
    public record CpuChunkPlan(
            long workItemsPerChunk,
            int chunkCount,
            int parallelism,
            int simdWidth) {
        public CpuChunkPlan {
            if (workItemsPerChunk < 0) throw new IllegalArgumentException("workItemsPerChunk cannot be negative");
            if (chunkCount < 0) throw new IllegalArgumentException("chunkCount cannot be negative");
            if (parallelism <= 0) throw new IllegalArgumentException("parallelism must be positive");
            if (simdWidth <= 0) throw new IllegalArgumentException("simdWidth must be positive");
        }
    }

    public CpuChunkPlan planCpuChunks(
            long workItems,
            int parallelism,
            int simdWidth,
            long minimumChunkWorkItems) {
        if (workItems < 0) throw new IllegalArgumentException("workItems cannot be negative");
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism must be positive");
        if (simdWidth <= 0) throw new IllegalArgumentException("simdWidth must be positive");
        if (minimumChunkWorkItems <= 0) {
            throw new IllegalArgumentException("minimumChunkWorkItems must be positive");
        }
        if (workItems == 0) return new CpuChunkPlan(0, 0, parallelism, simdWidth);

        long targetChunks = Math.min((long) parallelism, ceilDiv(workItems, minimumChunkWorkItems));
        targetChunks = Math.max(1L, targetChunks);
        long raw = ceilDiv(workItems, targetChunks);
        long aligned;
        try {
            aligned = alignUpExact(raw, simdWidth);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "SIMD-aligned CPU chunk size exceeds the planner's 64-bit range",
                    overflow);
        }
        int chunks = Math.toIntExact(ceilDiv(workItems, aligned));
        return new CpuChunkPlan(aligned, chunks, parallelism, simdWidth);
    }

    /**
     * Conservative non-interference check.
     *
     * <p>Disjoint partitions and disjoint field footprints may overlap in time.
     * Aliasing read/read accesses are independent. Any overlapping WRITE,
     * DISCARD, REDUCE, or ATOMIC access remains ordered unless a stronger
     * separately-certified rule is introduced later.</p>
     */
    public boolean independent(TaskSpec left, TaskSpec right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        for (Access a : left.accesses()) {
            for (Access b : right.accesses()) {
                if (!a.slice().mayAlias(b.slice())) continue;
                if (!a.mayOverlapFields(b)) continue;
                if (a.privilege() == Privilege.READ && b.privilege() == Privilege.READ) continue;
                return false;
            }
        }
        return true;
    }

    /**
     * Select a processor variant after effects have already established that
     * scheduling the task is semantically legal.
     */
    public Mapping map(
            TaskSpec task,
            PlacementHint hint,
            long workItems,
            Machine machine,
            Set<RegionIdentity> gpuResidentRootRegions) {
        return map(
                task,
                hint,
                workItems,
                -1L,
                machine,
                gpuResidentRootRegions);
    }

    /**
     * Placement with an optional estimated physical working-set size. Pass -1
     * when unknown. A known GPU working set must fit the declared device budget;
     * AUTO falls back to CPU, while explicit GPU placement fails closed.
     */
    public Mapping map(
            TaskSpec task,
            PlacementHint hint,
            long workItems,
            long estimatedWorkingSetBytes,
            Machine machine,
            Set<RegionIdentity> gpuResidentRootRegions) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(gpuResidentRootRegions, "gpuResidentRootRegions");
        if (workItems < 0) throw new IllegalArgumentException("workItems cannot be negative");
        if (estimatedWorkingSetBytes < -1L) {
            throw new IllegalArgumentException("estimatedWorkingSetBytes must be -1 (unknown) or non-negative");
        }

        Variant cpu = bestEligible(task, ProcessorKind.CPU, workItems);
        Variant gpu = bestEligible(task, ProcessorKind.GPU, workItems);
        boolean gpuMemoryFits = estimatedWorkingSetBytes < 0
                || estimatedWorkingSetBytes <= machine.gpuMemoryBudgetBytes();

        if (hint == PlacementHint.CPU) {
            if (cpu == null) throw new IllegalStateException("task has no eligible CPU variant");
            return new Mapping(cpu, ProcessorKind.CPU);
        }
        if (hint == PlacementHint.GPU) {
            if (!machine.gpuAvailable()) {
                throw new IllegalStateException("GPU placement requested but no GPU backend is available");
            }
            if (gpu == null) throw new IllegalStateException("task has no eligible GPU variant");
            if (!gpuMemoryFits) {
                throw new IllegalStateException(
                        "GPU placement working set exceeds device memory budget");
            }
            return new Mapping(gpu, ProcessorKind.GPU);
        }

        if (machine.gpuAvailable() && gpu != null && gpuMemoryFits) {
            Set<RegionIdentity> roots = new LinkedHashSet<>();
            for (Access access : task.accesses()) roots.add(access.slice().rootIdentity());
            long nonResident = roots.stream().filter(root -> !gpuResidentRootRegions.contains(root)).count();
            long transferMultiplier = Math.max(1L, nonResident + 1L);
            long crossover;
            try {
                crossover = Math.multiplyExact(machine.gpuCrossoverWorkItems(), transferMultiplier);
            } catch (ArithmeticException overflow) {
                crossover = Long.MAX_VALUE;
            }
            long threshold = Math.max(gpu.minimumWorkItems(), crossover);
            if (workItems >= threshold) return new Mapping(gpu, ProcessorKind.GPU);
        }

        if (cpu != null) return new Mapping(cpu, ProcessorKind.CPU);
        if (machine.gpuAvailable() && gpu != null && gpuMemoryFits) {
            return new Mapping(gpu, ProcessorKind.GPU);
        }
        if (machine.gpuAvailable() && gpu != null && !gpuMemoryFits) {
            throw new IllegalStateException(
                    "task has no eligible CPU variant and GPU working set exceeds device memory budget");
        }
        throw new IllegalStateException("task has no eligible variant for this machine/work estimate");
    }

    private static long ceilDiv(long numerator, long denominator) {
        return numerator / denominator + (numerator % denominator == 0 ? 0 : 1);
    }

    private static long alignUpExact(long value, long alignment) {
        long remainder = value % alignment;
        if (remainder == 0) return value;
        return Math.addExact(value, alignment - remainder);
    }

    private static Set<String> validateFieldPaths(Set<String> fields, String owner) {
        Objects.requireNonNull(fields, "fields");
        Set<String> copy = Set.copyOf(fields);
        for (String field : copy) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException(owner + " field paths cannot be blank");
            }
            String[] segments = field.split("\\.", -1);
            for (String segment : segments) {
                if (segment.isBlank()) {
                    throw new IllegalArgumentException(
                            owner + " field path contains an empty segment: " + field);
                }
            }
        }
        return copy;
    }

    private static boolean fieldPathsOverlap(String left, String right) {
        if (left.equals(right)) return true;
        return isStrictPathPrefix(left, right) || isStrictPathPrefix(right, left);
    }

    private static boolean isStrictPathPrefix(String prefix, String path) {
        return path.length() > prefix.length()
                && path.startsWith(prefix)
                && path.charAt(prefix.length()) == '.';
    }

    private Variant bestEligible(TaskSpec task, ProcessorKind processor, long workItems) {
        Variant best = null;
        for (Variant variant : task.variants()) {
            if (variant.processor() != processor || workItems < variant.minimumWorkItems()) continue;
            if (best == null
                    || variant.minimumWorkItems() > best.minimumWorkItems()
                    || (variant.minimumWorkItems() == best.minimumWorkItems()
                        && variant.name().compareTo(best.name()) < 0)) {
                best = variant;
            }
        }
        return best;
    }
}
