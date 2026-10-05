package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic dataflow planner over logical compute tasks.
 *
 * <p>Source order is preserved for interfering tasks. Independent tasks are
 * grouped into the same execution stage. Explicit flow edges add ordering but
 * never remove effect-derived dependencies.</p>
 */
public final class DataFlowGraph {
    public enum EdgeReason { EXPLICIT, DATA_DEPENDENCY }

    public record Node(
            String id,
            HeterogeneousTaskMapper.TaskSpec task,
            HeterogeneousTaskMapper.PlacementHint placement,
            long workItems) {
        public Node {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("flow node id cannot be blank");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(placement, "placement");
            if (workItems < 0) throw new IllegalArgumentException("workItems cannot be negative");
        }
    }

    public record Edge(String from, String to, EdgeReason reason) {
        public Edge {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                throw new IllegalArgumentException("flow edge endpoints cannot be blank");
            }
            if (from.equals(to)) throw new IllegalArgumentException("flow self-edge is invalid");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Plan(List<List<Node>> stages, List<Edge> edges) {
        public Plan {
            List<List<Node>> copied = new ArrayList<>(stages.size());
            for (List<Node> stage : stages) copied.add(List.copyOf(stage));
            stages = List.copyOf(copied);
            edges = List.copyOf(edges);
        }
    }

    private final HeterogeneousTaskMapper mapper;

    public DataFlowGraph(HeterogeneousTaskMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Builds a dependency DAG from source-ordered tasks plus optional explicit
     * flow edges. If task i and task j interfere and i appears first, i -> j is
     * inserted. The planner is conservative by design.
     */
    public Plan plan(List<Node> nodes, List<Edge> explicitEdges) {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        explicitEdges = List.copyOf(Objects.requireNonNull(explicitEdges, "explicitEdges"));

        LinkedHashMap<String, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate flow node id: " + node.id());
            }
        }

        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> indegree = new LinkedHashMap<>();
        for (Node node : nodes) {
            outgoing.put(node.id(), new LinkedHashSet<>());
            indegree.put(node.id(), 0);
        }

        ArrayList<Edge> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();

        for (Edge edge : explicitEdges) {
            requireKnown(byId, edge.from());
            requireKnown(byId, edge.to());
            addEdge(edge, outgoing, indegree, edges, edgeKeys);
        }

        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Node left = nodes.get(i);
                Node right = nodes.get(j);
                if (!mapper.independent(left.task(), right.task())) {
                    addEdge(
                            new Edge(left.id(), right.id(), EdgeReason.DATA_DEPENDENCY),
                            outgoing,
                            indegree,
                            edges,
                            edgeKeys);
                }
            }
        }

        ArrayList<List<Node>> stages = new ArrayList<>();
        LinkedHashSet<String> remaining = new LinkedHashSet<>(byId.keySet());

        while (!remaining.isEmpty()) {
            ArrayList<Node> stage = new ArrayList<>();
            for (String id : remaining) {
                if (indegree.get(id) == 0) stage.add(byId.get(id));
            }
            if (stage.isEmpty()) {
                throw new IllegalArgumentException("flow contains a dependency cycle");
            }

            stages.add(List.copyOf(stage));
            for (Node node : stage) {
                remaining.remove(node.id());
                for (String target : outgoing.get(node.id())) {
                    indegree.put(target, indegree.get(target) - 1);
                }
            }
        }

        return new Plan(stages, edges);
    }

    private static void requireKnown(Map<String, Node> byId, String id) {
        if (!byId.containsKey(id)) throw new IllegalArgumentException("unknown flow node: " + id);
    }

    private static void addEdge(
            Edge edge,
            Map<String, LinkedHashSet<String>> outgoing,
            Map<String, Integer> indegree,
            List<Edge> edges,
            Set<String> edgeKeys) {
        String key = edge.from() + "\u0000" + edge.to();
        if (!edgeKeys.add(key)) return;
        outgoing.get(edge.from()).add(edge.to());
        indegree.put(edge.to(), indegree.get(edge.to()) + 1);
        edges.add(edge);
    }
}
