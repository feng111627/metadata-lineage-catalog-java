package com.github.feng111627.metadatalineagecatalogjava.service;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldLineageEdge;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldRef;
import com.github.feng111627.metadatalineagecatalogjava.model.ImpactResult;
import com.github.feng111627.metadatalineagecatalogjava.model.LineageEdge;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 血缘登记与影响分析（数据集级 + 字段级）。
 *
 * 一致性：图状态是不可变快照（{@link GraphState}），写入在全局写锁内“拷贝-修改-整体替换”，
 * 读取无锁、只拿某一时刻的快照引用，因此登记与查询并发时，查询看到的永远是一张完整的图，
 * 不会出现只写了一半的中间态。
 *
 * 幂等：数据集边与字段边都以集合存储，同一条依赖重复提交只算一条。
 *
 * 成环：字段级依赖隐含其上下游数据集之间的依赖；环路检测在“显式数据集边 ∪ 字段边隐含数据集边”
 * 的并集图上进行，因此字段-字段、字段-数据集跨粒度的环都会被当场拒绝，统一错误码 LINEAGE_CYCLE。
 */
@Service
public class LineageService {

    private static final Comparator<FieldRef> FIELD_REF_ORDER =
            Comparator.comparing(FieldRef::dataset).thenComparing(FieldRef::field);

    private volatile GraphState state = GraphState.empty();
    private final ReentrantLock writeLock = new ReentrantLock();

    // ==================== 数据集级血缘 ====================

    /** 登记一条数据集级血缘边。重复提交幂等；成环抛 LINEAGE_CYCLE。 */
    public LineageEdge addEdge(String upstream, String downstream) {
        validateEndpoint(upstream, "上游");
        validateEndpoint(downstream, "下游");
        if (upstream.equals(downstream)) {
            throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                    "不允许数据集 [" + upstream + "] 依赖自身");
        }
        writeLock.lock();
        try {
            GraphState g = state;
            if (g.datasetDown.getOrDefault(upstream, Set.of()).contains(downstream)) {
                return new LineageEdge(upstream, downstream); // 幂等：重复提交直接返回
            }
            if (reachableDatasets(downstream, g).contains(upstream)) {
                throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                        "依赖 " + upstream + " -> " + downstream + " 会形成环路（"
                                + downstream + " 的下游已可达 " + upstream + "），已拒绝写入");
            }
            state = g.withDatasetEdge(upstream, downstream);
            return new LineageEdge(upstream, downstream);
        } finally {
            writeLock.unlock();
        }
    }

    /** 某数据集的全部传递下游数据集（不含自身），结果排序保证稳定。字段边隐含的数据集依赖也计入。 */
    public Set<String> downstreamImpact(String dataset) {
        validateEndpoint(dataset, "数据集");
        return new TreeSet<>(reachableDatasets(dataset, state));
    }

    /** 某数据集的全部传递上游数据集（不含自身），结果排序保证稳定。字段边隐含的数据集依赖也计入。 */
    public Set<String> upstreamLineage(String dataset) {
        validateEndpoint(dataset, "数据集");
        GraphState g = state;
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(dataset);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : g.datasetUpAll.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return new TreeSet<>(visited);
    }

    /** 当前全部数据集级血缘边（仅显式登记的边，排序，便于观测与测试）。 */
    public List<LineageEdge> listEdges() {
        GraphState g = state;
        List<LineageEdge> edges = new ArrayList<>();
        g.datasetDown.forEach((up, downs) -> downs.forEach(down -> edges.add(new LineageEdge(up, down))));
        edges.sort(Comparator.comparing(LineageEdge::upstream).thenComparing(LineageEdge::downstream));
        return edges;
    }

    // ==================== 字段级血缘 ====================

    /** 登记一条字段级血缘边。重复提交幂等；跨字段或跨粒度成环抛 LINEAGE_CYCLE。 */
    public FieldLineageEdge addFieldEdge(String upstreamDataset, String upstreamField,
                                         String downstreamDataset, String downstreamField) {
        validateEndpoint(upstreamDataset, "上游");
        validateEndpoint(upstreamField, "上游字段");
        validateEndpoint(downstreamDataset, "下游");
        validateEndpoint(downstreamField, "下游字段");
        String edgeText = upstreamDataset + "." + upstreamField + " -> "
                + downstreamDataset + "." + downstreamField;
        if (upstreamDataset.equals(downstreamDataset)) {
            // 同一数据集内的字段依赖（含同一字段自依赖）必然绕回本数据集，按成环拒绝
            throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                    "字段依赖 " + edgeText + " 位于同一数据集内，会形成环路，已拒绝写入");
        }
        writeLock.lock();
        try {
            GraphState g = state;
            FieldLineageEdge edge = new FieldLineageEdge(
                    upstreamDataset, upstreamField, downstreamDataset, downstreamField);
            if (g.fieldEdges.contains(edge)) {
                return edge; // 幂等：同一条字段依赖重复提交只算一条
            }
            // 字段边隐含 下游数据集 ->(依赖方向) 上游数据集；若下游数据集的传递下游已可达上游数据集则成环。
            // 该判定同时覆盖：字段与字段绕环、字段边与数据集边混合绕环。
            if (reachableDatasets(downstreamDataset, g).contains(upstreamDataset)) {
                throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                        "字段依赖 " + edgeText + " 会形成环路（"
                                + downstreamDataset + " 的下游已可达 " + upstreamDataset + "），已拒绝写入");
            }
            state = g.withFieldEdge(edge);
            return edge;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 数据集影响分析：变更某数据集后受波及的全部下游。
     * datasets：并集图（显式数据集边 ∪ 字段边隐含边）上的传递下游数据集，排除来源自身。
     * fields：下游数据集里经由字段级依赖被波及的下游字段（字段边的下游落点）。
     * 两者都排序、去重，相同输入结果稳定可复现。
     */
    public ImpactResult impactOfDataset(String dataset) {
        validateEndpoint(dataset, "数据集");
        GraphState g = state;
        Set<String> datasets = reachableDatasets(dataset, g);
        datasets.remove(dataset); // 防御性排除来源自身

        TreeSet<FieldRef> fields = new TreeSet<>(FIELD_REF_ORDER);
        for (FieldLineageEdge edge : g.fieldEdges) {
            if (datasets.contains(edge.downstreamDataset())) {
                fields.add(edge.downstream());
            }
        }
        return new ImpactResult(List.copyOf(new TreeSet<>(datasets)), List.copyOf(fields));
    }

    /**
     * 字段影响分析：变更某数据集的某字段后受波及的全部下游。
     * 沿字段级依赖边传递闭包；datasets 为被波及字段所属的下游数据集，fields 为被波及的下游字段。
     * 排除来源字段与其所属数据集；结果排序、去重，相同输入结果稳定可复现。
     */
    public ImpactResult impactOfField(String dataset, String field) {
        validateEndpoint(dataset, "数据集");
        validateEndpoint(field, "字段");
        GraphState g = state;
        FieldRef source = new FieldRef(dataset, field);

        Set<FieldRef> visited = new LinkedHashSet<>();
        Deque<FieldRef> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            FieldRef current = queue.poll();
            for (FieldRef next : g.fieldDown.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        visited.remove(source); // 来源字段不出现在下游结果里

        TreeSet<FieldRef> fields = new TreeSet<>(FIELD_REF_ORDER);
        fields.addAll(visited);
        TreeSet<String> datasets = new TreeSet<>();
        for (FieldRef ref : visited) {
            datasets.add(ref.dataset());
        }
        datasets.remove(dataset); // 防御性排除来源数据集
        return new ImpactResult(List.copyOf(datasets), List.copyOf(fields));
    }

    /** 当前全部字段级血缘边（按上下游数据集/字段排序，便于观测与测试）。 */
    public List<FieldLineageEdge> listFieldEdges() {
        GraphState g = state;
        List<FieldLineageEdge> edges = new ArrayList<>(g.fieldEdges);
        edges.sort(Comparator.comparing(FieldLineageEdge::upstreamDataset)
                .thenComparing(FieldLineageEdge::upstreamField)
                .thenComparing(FieldLineageEdge::downstreamDataset)
                .thenComparing(FieldLineageEdge::downstreamField));
        return edges;
    }

    // ==================== 内部工具 ====================

    /** 在并集图上求某数据集的传递下游（调用方需保证当前持有所基于的快照）。 */
    private Set<String> reachableDatasets(String start, GraphState g) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : g.datasetDownAll.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private void validateEndpoint(String name, String role) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, role + "名称不能为空");
        }
    }

    /**
     * 不可变血缘快照。所有 Map/Set 均为不可变嵌套结构；
     * datasetDownAll/datasetUpAll = 显式数据集边 ∪ 字段边隐含的数据集边。
     */
    private record GraphState(
            Map<String, Set<String>> datasetDown,
            Map<String, Set<String>> datasetUp,
            Map<String, Set<String>> datasetDownAll,
            Map<String, Set<String>> datasetUpAll,
            Map<FieldRef, Set<FieldRef>> fieldDown,
            Map<FieldRef, Set<FieldRef>> fieldUp,
            Set<FieldLineageEdge> fieldEdges) {

        static GraphState empty() {
            return new GraphState(
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        }

        GraphState withDatasetEdge(String upstream, String downstream) {
            Map<String, Set<String>> down = addAdj(datasetDown, upstream, downstream);
            Map<String, Set<String>> up = addAdj(datasetUp, downstream, upstream);
            return rebuild(down, up, fieldDown, fieldUp, fieldEdges);
        }

        GraphState withFieldEdge(FieldLineageEdge edge) {
            Set<FieldLineageEdge> edges = new HashSet<>(fieldEdges);
            edges.add(edge);
            Map<FieldRef, Set<FieldRef>> down =
                    addAdj(fieldDown, edge.upstream(), edge.downstream());
            Map<FieldRef, Set<FieldRef>> up =
                    addAdj(fieldUp, edge.downstream(), edge.upstream());
            return rebuild(datasetDown, datasetUp, down, up, edges);
        }

        private static <K, V> Map<K, Set<V>> addAdj(
                Map<K, Set<V>> adj, K key, V value) {
            Map<K, Set<V>> copy = new HashMap<>(adj);
            Set<V> targets = new HashSet<>(copy.getOrDefault(key, Set.of()));
            targets.add(value);
            copy.put(key, Set.copyOf(targets));
            return Map.copyOf(copy);
        }

        private static GraphState rebuild(
                Map<String, Set<String>> dsDown,
                Map<String, Set<String>> dsUp,
                Map<FieldRef, Set<FieldRef>> fDown,
                Map<FieldRef, Set<FieldRef>> fUp,
                Set<FieldLineageEdge> edges) {
            // 由字段边推导隐含的数据集级依赖，与显式数据集边取并集
            Map<String, Set<String>> allDown = mergeDatasetAdj(dsDown, edges, true);
            Map<String, Set<String>> allUp = mergeDatasetAdj(dsUp, edges, false);
            return new GraphState(dsDown, dsUp, allDown, allUp, fDown, fUp, Set.copyOf(edges));
        }

        private static Map<String, Set<String>> mergeDatasetAdj(
                Map<String, Set<String>> explicit, Set<FieldLineageEdge> edges, boolean downstreamView) {
            Map<String, Set<String>> merged = new HashMap<>();
            explicit.forEach((k, vs) -> merged.put(k, new HashSet<>(vs)));
            for (FieldLineageEdge e : edges) {
                String key = downstreamView ? e.upstreamDataset() : e.downstreamDataset();
                String value = downstreamView ? e.downstreamDataset() : e.upstreamDataset();
                merged.computeIfAbsent(key, k -> new HashSet<>()).add(value);
            }
            Map<String, Set<String>> immutable = new TreeMap<>();
            merged.forEach((k, vs) -> immutable.put(k, Set.copyOf(vs)));
            return Map.copyOf(immutable);
        }
    }
}
