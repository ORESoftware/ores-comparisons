package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class HeterogeneousTaskMapperTest {
    private final HeterogeneousTaskMapper mapper = new HeterogeneousTaskMapper();

    @Test
    void readReadMayOverlapButAliasingWritesSerialize() {
        var regionId = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(regionId);
        var cpu = new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var readerA = new HeterogeneousTaskMapper.TaskSpec(
                "reader-a",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(cpu));
        var readerB = new HeterogeneousTaskMapper.TaskSpec(
                "reader-b",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(cpu));
        var writer = new HeterogeneousTaskMapper.TaskSpec(
                "writer",
                List.of(HeterogeneousTaskMapper.Access.write(region)),
                List.of(cpu));

        assertTrue(mapper.independent(readerA, readerB));
        assertFalse(mapper.independent(readerA, writer));
        assertFalse(mapper.independent(writer, writer));
    }

    @Test
    void disjointFieldWritesOnSameRegionMayRunInParallel() {
        var regionId = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(regionId);
        var cpu = new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var positions = new HeterogeneousTaskMapper.TaskSpec(
                "positions",
                List.of(HeterogeneousTaskMapper.Access.writeFields(region, "x", "y")),
                List.of(cpu));
        var temperature = new HeterogeneousTaskMapper.TaskSpec(
                "temperature",
                List.of(HeterogeneousTaskMapper.Access.writeFields(region, "temperature")),
                List.of(cpu));
        var wholeRegion = new HeterogeneousTaskMapper.TaskSpec(
                "whole",
                List.of(HeterogeneousTaskMapper.Access.write(region)),
                List.of(cpu));

        assertTrue(mapper.independent(positions, temperature));
        assertFalse(mapper.independent(positions, wholeRegion));
    }

    @Test
    void certifiedDisjointPartitionsMayRunInParallel() {
        var regionId = HeterogeneousTaskMapper.allocateRegionIdentity();
        var family = HeterogeneousTaskMapper.certifyDisjointFamily();
        var left = HeterogeneousTaskMapper.RegionSlice.certified(regionId, 701, family);
        var right = HeterogeneousTaskMapper.RegionSlice.certified(regionId, 702, family);
        var cpu = new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var a = new HeterogeneousTaskMapper.TaskSpec(
                "a",
                List.of(HeterogeneousTaskMapper.Access.write(left)),
                List.of(cpu));
        var b = new HeterogeneousTaskMapper.TaskSpec(
                "b",
                List.of(HeterogeneousTaskMapper.Access.write(right)),
                List.of(cpu));

        assertTrue(mapper.independent(a, b));
    }

    @Test
    void independentlyCertifiedFamiliesDoNotForgeDisjointness() {
        var regionId = HeterogeneousTaskMapper.allocateRegionIdentity();
        var familyA = HeterogeneousTaskMapper.certifyDisjointFamily();
        var familyB = HeterogeneousTaskMapper.certifyDisjointFamily();
        var left = HeterogeneousTaskMapper.RegionSlice.certified(regionId, 801, familyA);
        var right = HeterogeneousTaskMapper.RegionSlice.certified(regionId, 802, familyB);

        assertTrue(left.mayAlias(right));
    }

    @Test
    void separatelyAllocatedRootsDoNotDependOnCallerChosenNumbers() {
        var rootA = HeterogeneousTaskMapper.allocateRegionIdentity();
        var rootB = HeterogeneousTaskMapper.allocateRegionIdentity();
        var left = HeterogeneousTaskMapper.RegionSlice.root(rootA);
        var right = HeterogeneousTaskMapper.RegionSlice.root(rootB);

        assertFalse(left.mayAlias(right));
    }

    @Test
    void variantSelectionIsDeterministicRatherThanDeclarationOrdered() {
        var regionId = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(regionId);
        var generic = new HeterogeneousTaskMapper.Variant(
                "generic", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var zSpecialized = new HeterogeneousTaskMapper.Variant(
                "z-specialized", HeterogeneousTaskMapper.ProcessorKind.CPU, 100);
        var aSpecialized = new HeterogeneousTaskMapper.Variant(
                "a-specialized", HeterogeneousTaskMapper.ProcessorKind.CPU, 100);

        var forward = new HeterogeneousTaskMapper.TaskSpec(
                "forward",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(generic, zSpecialized, aSpecialized));
        var reverse = new HeterogeneousTaskMapper.TaskSpec(
                "reverse",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(aSpecialized, zSpecialized, generic));
        var machine = new HeterogeneousTaskMapper.Machine(false, 0);

        assertEquals(
                "a-specialized",
                mapper.map(
                        forward,
                        HeterogeneousTaskMapper.PlacementHint.CPU,
                        200,
                        machine,
                        Set.of()).variant().name());
        assertEquals(
                "a-specialized",
                mapper.map(
                        reverse,
                        HeterogeneousTaskMapper.PlacementHint.CPU,
                        200,
                        machine,
                        Set.of()).variant().name());
    }

    @Test
    void duplicateVariantNamesAreRejected() {
        var cpu = new HeterogeneousTaskMapper.Variant(
                "same", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var gpu = new HeterogeneousTaskMapper.Variant(
                "same", HeterogeneousTaskMapper.ProcessorKind.GPU, 0);

        assertThrows(
                IllegalArgumentException.class,
                () -> new HeterogeneousTaskMapper.TaskSpec(
                        "ambiguous",
                        List.of(),
                        List.of(cpu, gpu)));
    }

    @Test
    void autoPlacementAccountsForGpuCrossoverAndDataResidency() {
        var regionId = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(regionId);
        var task = new HeterogeneousTaskMapper.TaskSpec(
                "transform",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(
                        new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0),
                        new HeterogeneousTaskMapper.Variant("gpu", HeterogeneousTaskMapper.ProcessorKind.GPU, 100)));
        var machine = new HeterogeneousTaskMapper.Machine(true, 100);

        assertEquals(
                HeterogeneousTaskMapper.ProcessorKind.CPU,
                mapper.map(task, HeterogeneousTaskMapper.PlacementHint.AUTO, 150, machine, Set.of()).processor());

        assertEquals(
                HeterogeneousTaskMapper.ProcessorKind.GPU,
                mapper.map(task, HeterogeneousTaskMapper.PlacementHint.AUTO, 150, machine, Set.of(regionId)).processor());

        assertEquals(
                HeterogeneousTaskMapper.ProcessorKind.GPU,
                mapper.map(task, HeterogeneousTaskMapper.PlacementHint.AUTO, 250, machine, Set.of()).processor());
    }

    @Test
    void explicitGpuPlacementFailsClosedWithoutGpuBackend() {
        var task = new HeterogeneousTaskMapper.TaskSpec(
                "gpu-only",
                List.of(),
                List.of(new HeterogeneousTaskMapper.Variant(
                        "gpu",
                        HeterogeneousTaskMapper.ProcessorKind.GPU,
                        0)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> mapper.map(
                        task,
                        HeterogeneousTaskMapper.PlacementHint.GPU,
                        1,
                        new HeterogeneousTaskMapper.Machine(false, 0),
                        Set.of()));
        assertTrue(failure.getMessage().contains("no GPU backend"));
    }

    @Test
    void reductionsRemainOrderedUntilOperatorSafetyIsRegistered() {
        var regionId = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(regionId);
        var cpu = new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var reduceA = new HeterogeneousTaskMapper.TaskSpec(
                "reduce-a",
                List.of(HeterogeneousTaskMapper.Access.reduce(region, "sum")),
                List.of(cpu));
        var reduceB = new HeterogeneousTaskMapper.TaskSpec(
                "reduce-b",
                List.of(HeterogeneousTaskMapper.Access.reduce(region, "sum")),
                List.of(cpu));

        assertFalse(mapper.independent(reduceA, reduceB));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Access.reduce(region, ""));
    }
}
