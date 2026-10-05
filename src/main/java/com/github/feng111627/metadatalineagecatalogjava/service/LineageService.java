package com.github.feng111627.metadatalineagecatalogjava.service;

import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.domain.ImpactReport;
import com.github.feng111627.metadatalineagecatalogjava.domain.LineageEdge;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.store.CatalogStore;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.LineageRegisterRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 血缘登记与影响分析服务。
 *
 * <p>边方向为 上游 -> 下游：</p>
 * <ul>
 *   <li>数据集级边：两端字段都为空；字段级边：两端字段都非空，
 *       且字段必须存在于对应数据集的最新版本中。</li>
 *   <li>重复提交同一条边（四元组相同）幂等，不产生第二条结果。</li>
 *   <li>加入新边若会使统一血缘图出现环路（含自依赖、跨粒度环），以
 *       {@link CatalogErrorCode#LINEAGE_CYCLE} 明确拒绝。</li>
 *   <li>影响分析沿同一张统一图 BFS：同一数据集的“数据集节点 ↔ 字段节点”双向连通，
 *       跨数据集只沿登记边方向传播，结果按跳数与名称稳定排序。</li>
 * </ul>
 */
@Service
public class LineageService {

    private final CatalogStore store;

    public LineageService(CatalogStore store) {
        this.store = store;
    }

    /**
     * 登记血缘边。
     *
     * @return true 新登记；false 边已存在（幂等）
     */
    public synchronized boolean registerEdge(LineageRegisterRequest request) {
        if (request == null) {
            throw new CatalogException(CatalogErrorCode.INVALID_REQUEST, "request body is required");
        }
        String upstreamDataset = require(request.upstreamDataset(), "upstreamDataset");
        String downstreamDataset = require(request.downstreamDataset(), "downstreamDataset");
        String upstreamField = blankToNull(request.upstreamField());
        String downstreamField = blankToNull(request.downstreamField());

        if ((upstreamField == null) != (downstreamField == null)) {
            throw new CatalogException(CatalogErrorCode.INVALID_REQUEST,
                    "lineage edge must be dataset-level (both fields empty) or field-level (both fields set)");
        }

        requireDataset(upstreamDataset);
        requireDataset(downstreamDataset);
        if (upstreamField != null) {
            requireField(upstreamDataset, upstreamField);
            requireField(downstreamDataset, downstreamField);
        }

        LineageEdge candidate = new LineageEdge(
                upstreamDataset, upstreamField, downstreamDataset, downstreamField,
                Instant.now(), request.createdBy());

        List<LineageEdge> snapshot = store.edges();
        if (snapshot.contains(candidate)) {
            return false; // 幂等：重复提交同一条依赖
        }

        Map<String, List<String>> graph = buildCycleGraph(snapshot);
        addCandidateToCycleGraph(graph, candidate);
        String downNode = nodeKey(candidate.downstreamDataset(), candidate.downstreamField());
        String upNode = nodeKey(candidate.upstreamDataset(), candidate.upstreamField());
        if (reaches(graph, downNode, upNode)) {
            throw new CatalogException(CatalogErrorCode.LINEAGE_CYCLE,
                    "registering lineage " + candidate
                            + " would create a cycle; cyclic dependencies are rejected");
        }
        return store.addEdgeIfAbsent(candidate);
    }

    public List<LineageEdge> edges() {
        return store.edges();
    }

    /**
     * 分析 dataset[.field] 发生变更时可波及的全部下游节点。
     *
     * <p>成员关系（数据集节点 ↔ 同数据集字段节点）不占跳数：从源数据集的任一节点都可
     * 理解为可能携带本次变更；真正的跳数只统计跨数据集的血缘边。源数据集自身的节点
     * 不会出现在受影响列表中。{@code via} 为进入该节点的直接上游节点。</p>
     */
    public ImpactReport analyzeImpact(String dataset, String field) {
        String sourceField = blankToNull(field);
        require(dataset, "dataset");
        requireDataset(dataset);
        if (sourceField != null) {
            requireField(dataset, sourceField);
        }

        Map<String, List<String>> lineageAdjacency = buildLineageAdjacency(store.edges());
        Map<String, List<String>> membership = buildMembership(store.edges());

        record QueueNode(String node, int distance, String via) {
        }

        Deque<QueueNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        // 源数据集内所有节点都在距离 0 的闭包中，但不进入受影响列表
        for (String member : membershipClosure(nodeKey(dataset, sourceField), membership)) {
            visited.add(member);
            queue.add(new QueueNode(member, 0, ""));
        }

        Map<String, ImpactReport.ImpactedNode> impacted = new LinkedHashMap<>();
        Set<String> countedEdges = new HashSet<>();

        while (!queue.isEmpty()) {
            QueueNode current = queue.poll();
            for (String next : lineageAdjacency.getOrDefault(current.node(), List.of())) {
                countedEdges.add(current.node() + "->" + next);
                if (visited.add(next)) {
                    int distance = current.distance() + 1;
                    String via = display(current.node());
                    impacted.put(next, new ImpactReport.ImpactedNode(
                            datasetOf(next), fieldOf(next), distance, via));
                    // 新到达节点展开其同数据集成员闭包，距离与 via 不变（成员关系不占跳数）
                    for (String member : membershipClosure(next, membership)) {
                        if (visited.add(member)) {
                            impacted.put(member, new ImpactReport.ImpactedNode(
                                    datasetOf(member), fieldOf(member), distance, via));
                            queue.add(new QueueNode(member, distance, via));
                        }
                    }
                    queue.add(new QueueNode(next, distance, via));
                }
            }
        }

        List<ImpactReport.ImpactedNode> sorted = impacted.values().stream()
                .sorted((a, b) -> {
                    int byDistance = Integer.compare(a.distance(), b.distance());
                    if (byDistance != 0) {
                        return byDistance;
                    }
                    return a.endpoint().compareTo(b.endpoint());
                })
                .toList();
        return new ImpactReport(dataset, sourceField, sorted, countedEdges.size());
    }

    /**
     * 血缘邻接：只包含跨数据集的边方向转移
     * （数据集级 D:up -> D:down；字段级 F:up.f -> F:down.g）。
     */
    private static Map<String, List<String>> buildLineageAdjacency(List<LineageEdge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (LineageEdge edge : edges) {
            adjacency.computeIfAbsent(
                            nodeKey(edge.upstreamDataset(), edge.upstreamField()),
                            k -> new ArrayList<>())
                    .add(nodeKey(edge.downstreamDataset(), edge.downstreamField()));
        }
        return adjacency;
    }

    /**
     * 成员关系：数据集节点与其字段节点双向连通。
     */
    private static Map<String, List<String>> buildMembership(List<LineageEdge> edges) {
        Map<String, List<String>> membership = new HashMap<>();
        for (LineageEdge edge : edges) {
            if (edge.upstreamField() != null) {
                connectMembership(membership,
                        "D:" + edge.upstreamDataset(),
                        "F:" + edge.upstreamDataset() + "." + edge.upstreamField());
                connectMembership(membership,
                        "D:" + edge.downstreamDataset(),
                        "F:" + edge.downstreamDataset() + "." + edge.downstreamField());
            }
        }
        return membership;
    }

    /** 返回节点所在数据集闭包内的全部节点（包含自身）。 */
    private static Set<String> membershipClosure(String node, Map<String, List<String>> membership) {
        Set<String> closure = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(node);
        closure.add(node);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String member : membership.getOrDefault(current, List.of())) {
                if (closure.add(member)) {
                    stack.push(member);
                }
            }
        }
        return closure;
    }

    /**
     * 环路检测图：在血缘有向边之外叠加“数据集 \u2194 字段”双向成员关系，
     * 使数据集级与字段级边构成的跨粒度环同样可见。
     */
    private static Map<String, List<String>> buildCycleGraph(List<LineageEdge> edges) {
        Map<String, List<String>> graph = new HashMap<>(buildLineageAdjacency(edges));
        for (var entry : buildMembership(edges).entrySet()) {
            graph.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        return graph;
    }

    private static void addCandidateToCycleGraph(Map<String, List<String>> graph, LineageEdge edge) {
        graph.computeIfAbsent(
                        nodeKey(edge.upstreamDataset(), edge.upstreamField()),
                        k -> new ArrayList<>())
                .add(nodeKey(edge.downstreamDataset(), edge.downstreamField()));
        if (edge.upstreamField() != null) {
            connectMembership(graph, "D:" + edge.upstreamDataset(),
                    "F:" + edge.upstreamDataset() + "." + edge.upstreamField());
            connectMembership(graph, "D:" + edge.downstreamDataset(),
                    "F:" + edge.downstreamDataset() + "." + edge.downstreamField());
        }
    }

    private static void connectMembership(Map<String, List<String>> adjacency,
                                          String datasetNode, String fieldNode) {
        adjacency.computeIfAbsent(datasetNode, k -> new ArrayList<>()).add(fieldNode);
        adjacency.computeIfAbsent(fieldNode, k -> new ArrayList<>()).add(datasetNode);
    }

    private static boolean reaches(Map<String, List<String>> adjacency, String from, String target) {
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (node.equals(target)) {
                return true;
            }
            for (String next : adjacency.getOrDefault(node, List.of())) {
                if (seen.add(next)) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    private void requireDataset(String dataset) {
        if (store.latestVersion(dataset).isEmpty()) {
            throw new CatalogException(CatalogErrorCode.LINEAGE_ENDPOINT_NOT_FOUND,
                    "dataset '" + dataset + "' is not registered");
        }
    }

    private void requireField(String dataset, String field) {
        DatasetVersion latest = store.latestVersion(dataset).orElseThrow(() ->
                new CatalogException(CatalogErrorCode.LINEAGE_ENDPOINT_NOT_FOUND,
                        "dataset '" + dataset + "' is not registered"));
        boolean exists = latest.fields().stream().anyMatch(f -> f.name().equals(field));
        if (!exists) {
            throw new CatalogException(CatalogErrorCode.LINEAGE_ENDPOINT_NOT_FOUND,
                    "field '" + field + "' does not exist in latest version of dataset '"
                            + dataset + "' (v" + latest.version() + ")");
        }
    }

    private static String require(String value, String param) {
        if (value == null || value.isBlank()) {
            throw new CatalogException(CatalogErrorCode.INVALID_REQUEST, param + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String nodeKey(String dataset, String field) {
        return field == null ? "D:" + dataset : "F:" + dataset + "." + field;
    }

    private static String display(String node) {
        return node.substring(2);
    }

    private static String datasetOf(String node) {
        String body = node.substring(2);
        int dot = node.startsWith("F:") ? body.lastIndexOf('.') : -1;
        return dot < 0 ? body : body.substring(0, dot);
    }

    private static String fieldOf(String node) {
        if (node.startsWith("D:")) {
            return null;
        }
        String body = node.substring(2);
        return body.substring(body.lastIndexOf('.') + 1);
    }
}
