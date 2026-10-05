package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class HeterogeneousTaskMapperTest {
    private final HeterogeneousTaskMapper mapper = new HeterogeneousTaskMapper();

    @Test
    void readReadMayOverlapButMutatingPrivilegesSerialize() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(root);
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var readerA = task("reader-a", HeterogeneousTaskMapper.Access.read(region), cpu);
        var readerB = task("reader-b", HeterogeneousTaskMapper.Access.read(region), cpu);
        var writer = task("writer", HeterogeneousTaskMapper.Access.write(region), cpu);
        var discard = task("discard", HeterogeneousTaskMapper.Access.discard(region), cpu);
        var reduce = task("reduce", HeterogeneousTaskMapper.Access.reduce(region, "sum"), cpu);
        var atomic = task("atomic", HeterogeneousTaskMapper.Access.atomic(region), cpu);

        assertTrue(mapper.independent(readerA, readerB));
        assertFalse(mapper.independent(readerA, writer));
        assertFalse(mapper.independent(readerA, discard));
        assertFalse(mapper.independent(readerA, reduce));
        assertFalse(mapper.independent(readerA, atomic));
        assertFalse(mapper.independent(writer, discard));
    }

    @Test
    void parentAndChildFieldPathsConflictButSiblingLeavesRemainIndependent() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(root);
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var wholePosition = task(
                "position",
                HeterogeneousTaskMapper.Access.writeFields(region, "position"),
                cpu);
        var positionX = task(
                "position-x",
                HeterogeneousTaskMapper.Access.readFields(region, "position.x"),
                cpu);
        var positionY = task(
                "position-y",
                HeterogeneousTaskMapper.Access.writeFields(region, "position.y"),
                cpu);

        assertFalse(
                mapper.independent(wholePosition, positionX),
                "a parent field footprint must overlap its nested child");
        assertTrue(
                mapper.independent(positionX, positionY),
                "distinct leaf fields may still execute independently");

        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Access.readFields(region, "position..x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Access.readFields(region, ".position"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Access.readFields(region, "position."));
    }

    @Test
    void disjointFieldWritesMayRunInParallel() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(root);
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var position = task(
                "position",
                HeterogeneousTaskMapper.Access.writeFields(region, "x", "y", "z"),
                cpu);
        var temperature = task(
                "temperature",
                HeterogeneousTaskMapper.Access.writeFields(region, "temperature"),
                cpu);
        var whole = task("whole", HeterogeneousTaskMapper.Access.write(region), cpu);

        assertTrue(mapper.independent(position, temperature));
        assertFalse(mapper.independent(position, whole));
    }

    @Test
    void certifiedSlicesCannotReuseReservedRootIdentity() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var family = HeterogeneousTaskMapper.certifyDisjointFamily();

        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.RegionSlice.certified(root, 1L, family));
    }

    @Test
    void certifiedSiblingSlicesAreIndependentButProofsCannotBeMixed() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var family = HeterogeneousTaskMapper.certifyDisjointFamily();
        var otherFamily = HeterogeneousTaskMapper.certifyDisjointFamily();

        var left = HeterogeneousTaskMapper.RegionSlice.certified(root, 11, family);
        var right = HeterogeneousTaskMapper.RegionSlice.certified(root, 12, family);
        var unrelatedProof = HeterogeneousTaskMapper.RegionSlice.certified(root, 13, otherFamily);

        assertFalse(left.mayAlias(right));
        assertTrue(left.mayAlias(unrelatedProof));
    }

    @Test
    void regionViewsAreZeroCopyLogicalProjectionsWithIndependentLayoutMetadata() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var slice = HeterogeneousTaskMapper.RegionSlice.root(root);

        var view = HeterogeneousTaskMapper.RegionView.whole(slice)
                .fields("position", "velocity")
                .withLayout(HeterogeneousTaskMapper.Layout.soa());

        assertSame(slice, view.slice());
        assertEquals(Set.of("position", "velocity"), view.fields());
        assertEquals(HeterogeneousTaskMapper.LayoutKind.SOA, view.layout().kind());

        var access = HeterogeneousTaskMapper.Access.fromView(
                view, HeterogeneousTaskMapper.Privilege.READ);
        assertSame(slice, access.slice());
        assertEquals(view.fields(), access.fields());
    }

    @Test
    void layoutContractsRejectMalformedShapes() {
        assertEquals(
                HeterogeneousTaskMapper.LayoutKind.AOSOA,
                HeterogeneousTaskMapper.Layout.aosoa(32).kind());
        assertEquals(
                List.of(16, 16),
                HeterogeneousTaskMapper.Layout.tiled(16, 16).tileShape());

        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Layout.aosoa(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Layout.tiled(16, 0));
    }

    @Test
    void autoPlacementAccountsForResidencyAndIsDeterministic() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(root);
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var gpuZ = new HeterogeneousTaskMapper.Variant(
                "z-gpu", HeterogeneousTaskMapper.ProcessorKind.GPU, 100);
        var gpuA = new HeterogeneousTaskMapper.Variant(
                "a-gpu", HeterogeneousTaskMapper.ProcessorKind.GPU, 100);

        var task = new HeterogeneousTaskMapper.TaskSpec(
                "integrate",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(cpu, gpuZ, gpuA));
        var machine = new HeterogeneousTaskMapper.Machine(true, 100);

        assertEquals(
                HeterogeneousTaskMapper.ProcessorKind.CPU,
                mapper.map(task, HeterogeneousTaskMapper.PlacementHint.AUTO, 150, machine, Set.of())
                        .processor());

        var resident = mapper.map(
                task,
                HeterogeneousTaskMapper.PlacementHint.AUTO,
                150,
                machine,
                Set.of(root));
        assertEquals(HeterogeneousTaskMapper.ProcessorKind.GPU, resident.processor());
        assertEquals("a-gpu", resident.variant().name());
    }

    @Test
    void explicitGpuPlacementFailsClosed() {
        var gpuOnly = new HeterogeneousTaskMapper.TaskSpec(
                "gpu-only",
                List.of(),
                List.of(new HeterogeneousTaskMapper.Variant(
                        "gpu", HeterogeneousTaskMapper.ProcessorKind.GPU, 0)));

        var failure = assertThrows(
                IllegalStateException.class,
                () -> mapper.map(
                        gpuOnly,
                        HeterogeneousTaskMapper.PlacementHint.GPU,
                        1,
                        new HeterogeneousTaskMapper.Machine(false, 0),
                        Set.of()));
        assertTrue(failure.getMessage().contains("no GPU backend"));
    }

    @Test
    void reductionRequiresNamedOperatorAndGpuSimdFlagIsRejected() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(root);

        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Access.reduce(region, ""));

        assertThrows(
                IllegalArgumentException.class,
                () -> new HeterogeneousTaskMapper.Variant(
                        "gpu",
                        HeterogeneousTaskMapper.ProcessorKind.GPU,
                        0,
                        true,
                        true,
                        HeterogeneousTaskMapper.Layout.auto()));
    }


    @Test
    void cpuChunkPlanningUsesCoarseSimdAlignedSchedulerUnits() {
        var plan = mapper.planCpuChunks(10_000, 8, 16, 512);

        assertTrue(plan.chunkCount() <= 8);
        assertTrue(plan.workItemsPerChunk() >= 512);
        assertEquals(0, plan.workItemsPerChunk() % 16);
        assertTrue((long) plan.chunkCount() * plan.workItemsPerChunk() >= 10_000);

        var tiny = mapper.planCpuChunks(7, 32, 8, 256);
        assertEquals(1, tiny.chunkCount());
        assertEquals(8, tiny.workItemsPerChunk(),
                "small workloads should stay one vector-aligned chunk rather than one task per item");
    }

    @Test
    void cpuChunkPlanningFailsClosedWhenSimdAlignmentWouldOverflow() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.planCpuChunks(Long.MAX_VALUE, 1, 8, 1));
        assertTrue(failure.getMessage().contains("64-bit"));
    }

    @Test
    void gpuPlacementHonorsKnownWorkingSetMemoryBudget() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(root);
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var gpu = new HeterogeneousTaskMapper.Variant(
                "gpu", HeterogeneousTaskMapper.ProcessorKind.GPU, 0);
        var task = new HeterogeneousTaskMapper.TaskSpec(
                "integrate",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(cpu, gpu));
        var machine = new HeterogeneousTaskMapper.Machine(true, 0, 1024);

        var auto = mapper.map(
                task,
                HeterogeneousTaskMapper.PlacementHint.AUTO,
                10_000,
                2048,
                machine,
                Set.of(root));
        assertEquals(HeterogeneousTaskMapper.ProcessorKind.CPU, auto.processor(),
                "AUTO must not choose a GPU whose memory budget cannot hold the known working set");

        var explicitFailure = assertThrows(
                IllegalStateException.class,
                () -> mapper.map(
                        task,
                        HeterogeneousTaskMapper.PlacementHint.GPU,
                        10_000,
                        2048,
                        machine,
                        Set.of(root)));
        assertTrue(explicitFailure.getMessage().contains("memory budget"));
    }

    private static HeterogeneousTaskMapper.TaskSpec task(
            String name,
            HeterogeneousTaskMapper.Access access,
            HeterogeneousTaskMapper.Variant variant) {
        return new HeterogeneousTaskMapper.TaskSpec(name, List.of(access), List.of(variant));
    }
}
