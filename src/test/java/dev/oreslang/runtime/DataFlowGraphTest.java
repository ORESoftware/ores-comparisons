package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DataFlowGraphTest {
    private final HeterogeneousTaskMapper mapper = new HeterogeneousTaskMapper();
    private final DataFlowGraph graph = new DataFlowGraph(mapper);

    @Test
    void independentTasksShareAStageAndConflictingTaskFollows() {
        var root = HeterogeneousTaskMapper.allocateRegionIdentity();
        var region = HeterogeneousTaskMapper.RegionSlice.root(root);
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var positions = node(
                "positions",
                new HeterogeneousTaskMapper.TaskSpec(
                        "positions",
                        List.of(HeterogeneousTaskMapper.Access.writeFields(region, "position")),
                        List.of(cpu)));
        var temperature = node(
                "temperature",
                new HeterogeneousTaskMapper.TaskSpec(
                        "temperature",
                        List.of(HeterogeneousTaskMapper.Access.writeFields(region, "temperature")),
                        List.of(cpu)));
        var consumeAll = node(
                "consume",
                new HeterogeneousTaskMapper.TaskSpec(
                        "consume",
                        List.of(HeterogeneousTaskMapper.Access.read(region)),
                        List.of(cpu)));

        var plan = graph.plan(List.of(positions, temperature, consumeAll), List.of());

        assertEquals(2, plan.stages().size());
        assertEquals(List.of(positions, temperature), plan.stages().getFirst());
        assertEquals(List.of(consumeAll), plan.stages().get(1));
        assertEquals(2, plan.edges().size());
        assertTrue(plan.edges().stream().allMatch(
                edge -> edge.reason() == DataFlowGraph.EdgeReason.DATA_DEPENDENCY));
    }

    @Test
    void explicitEdgesOrderOtherwiseIndependentTasks() {
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var left = node(
                "left",
                new HeterogeneousTaskMapper.TaskSpec("left", List.of(), List.of(cpu)));
        var right = node(
                "right",
                new HeterogeneousTaskMapper.TaskSpec("right", List.of(), List.of(cpu)));

        var plan = graph.plan(
                List.of(left, right),
                List.of(new DataFlowGraph.Edge(
                        "left", "right", DataFlowGraph.EdgeReason.EXPLICIT)));

        assertEquals(List.of(List.of(left), List.of(right)), plan.stages());
    }

    @Test
    void cyclesFailClosed() {
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var left = node(
                "left",
                new HeterogeneousTaskMapper.TaskSpec("left", List.of(), List.of(cpu)));
        var right = node(
                "right",
                new HeterogeneousTaskMapper.TaskSpec("right", List.of(), List.of(cpu)));

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> graph.plan(
                        List.of(left, right),
                        List.of(
                                new DataFlowGraph.Edge(
                                        "left", "right", DataFlowGraph.EdgeReason.EXPLICIT),
                                new DataFlowGraph.Edge(
                                        "right", "left", DataFlowGraph.EdgeReason.EXPLICIT))));
        assertTrue(failure.getMessage().contains("cycle"));
    }

    @Test
    void unknownExplicitEndpointsAndDuplicateNodeIdsFailClosed() {
        var cpu = new HeterogeneousTaskMapper.Variant(
                "cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var left = node(
                "left",
                new HeterogeneousTaskMapper.TaskSpec("left", List.of(), List.of(cpu)));

        assertThrows(
                IllegalArgumentException.class,
                () -> graph.plan(
                        List.of(left),
                        List.of(new DataFlowGraph.Edge(
                                "left", "missing", DataFlowGraph.EdgeReason.EXPLICIT))));

        assertThrows(
                IllegalArgumentException.class,
                () -> graph.plan(List.of(left, left), List.of()));
    }

    private static DataFlowGraph.Node node(
            String id,
            HeterogeneousTaskMapper.TaskSpec task) {
        return new DataFlowGraph.Node(
                id,
                task,
                HeterogeneousTaskMapper.PlacementHint.AUTO,
                1);
    }
}
