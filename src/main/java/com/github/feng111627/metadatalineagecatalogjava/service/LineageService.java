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
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 血缘登记与影响分析（数据集级 + 字段级，两种粒度混在同一张依赖图上判定）。
 * - 边以集合存储：同一条依赖（数据集对或字段对）重复提交幂等，不产生重复记录。
 * - 写入前在统一图上做环路检测：字段环、数据集环、字段边与数据集边跨粒度绕成的环，
 *   一律以 LINEAGE_CYCLE 明确拒绝，绝不静默写入。
 * - 影响分析返回被波及的下游数据集与下游字段，去重、字典序排序，结果稳定可复现；
 *   来源数据集自身（含其字段）不出现在结果中。
 * - 读写锁保证：登记与查询并发时，每次查询看到的都是某一版完整的图，不会读到半成品。
 */
@Service
public class LineageService {

    /** 数据集级边：upstream -> downstream 集合，反向视图保持一致。 */
    private final Map<String, Set<String>> dsDownstream = new HashMap<>();
    private final Map<String, Set<String>> dsUpstream = new HashMap<>();
    /** 字段级边：upstream 字段 -> downstream 字段集合，反向视图保持一致。 */
    private final Map<FieldRef, Set<FieldRef>> fieldDownstream = new HashMap<>();
    private final Map<FieldRef, Set<FieldRef>> fieldUpstream = new HashMap<>();
    /** 图中已出现的字段坐标，按所属数据集归集，供跨粒度成环检测的“归属”展开使用。 */
    private final Map<String, Set<String>> knownFieldsByDataset = new HashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ==================== 登记 ====================

    /** 登记一条数据集级血缘边。重复提交幂等；成环（含跨粒度）抛 LINEAGE_CYCLE。 */
    public LineageEdge addEdge(String upstream, String downstream) {
        validateName(upstream, "上游数据集");
        validateName(downstream, "下游数据集");
        if (upstream.equals(downstream)) {
            throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                    "不允许数据集 [" + upstream + "] 依赖自身");
        }
        lock.writeLock().lock();
        try {
            if (dsDownstream.getOrDefault(upstream, Set.of()).contains(downstream)) {
                return new LineageEdge(upstream, downstream); // 幂等
            }
            // 新增 B -> A 成环 ⇔ 现有图上从 A 能回到 B（直接到达 B 或 B 的任一字段）
            if (reachesForCycle(Node.dataset(downstream), upstream, null, null)) {
                throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                        "依赖 " + upstream + " -> " + downstream + " 会形成环路（"
                                + downstream + " 的下游已可达 " + upstream + "），已拒绝写入");
            }
            dsDownstream.computeIfAbsent(upstream, k -> new HashSet<>()).add(downstream);
            dsUpstream.computeIfAbsent(downstream, k -> new HashSet<>()).add(upstream);
            return new LineageEdge(upstream, downstream);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 登记一条字段级血缘边。重复提交幂等；成环（字段环或跨粒度环）抛 LINEAGE_CYCLE。 */
    public FieldLineageEdge addFieldEdge(FieldRef upstream, FieldRef downstream) {
        validateRef(upstream, "上游字段");
        validateRef(downstream, "下游字段");
        if (upstream.equals(downstream)) {
            throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                    "不允许字段 [" + upstream.dataset() + "." + upstream.field() + "] 依赖自身");
        }
        lock.writeLock().lock();
        try {
            if (fieldDownstream.getOrDefault(upstream, Set.of()).contains(downstream)) {
                return new FieldLineageEdge(upstream, downstream); // 幂等
            }
            // 新增 u -> v 成环 ⇔ 现有图上从 v 能沿（字段边/数据集边/字段归属）回到 u。
            // 注意 u 可能是尚未入图的新字段，归属展开时需把它临时算作其数据集下的字段。
            if (reachesForCycle(Node.field(downstream), null, upstream, upstream)) {
                throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                        "字段依赖 " + format(upstream) + " -> " + format(downstream)
                                + " 会形成环路（含跨数据集级血缘的混合环），已拒绝写入");
            }
            fieldDownstream.computeIfAbsent(upstream, k -> new HashSet<>()).add(downstream);
            fieldUpstream.computeIfAbsent(downstream, k -> new HashSet<>()).add(upstream);
            knownFieldsByDataset.computeIfAbsent(upstream.dataset(), k -> new HashSet<>()).add(upstream.field());
            knownFieldsByDataset.computeIfAbsent(downstream.dataset(), k -> new HashSet<>()).add(downstream.field());
            return new FieldLineageEdge(upstream, downstream);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 影响分析 ====================

    /**
     * 数据集变更影响：该数据集视为“整体变更”。
     * 口径见 {@link #computeImpact}：整体波及的数据集展开其全部字段边；
     * 仅被个别字段触达的数据集只沿数据集边继续整体波及，不错误株连其内其他字段。
     */
    public ImpactResult impactOfDataset(String dataset) {
        validateName(dataset, "数据集");
        lock.readLock().lock();
        try {
            return computeImpact(dataset, null, true);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 字段变更影响：从具体字段出发。
     * 其所属数据集只是“被个别字段触达”，不展开该数据集其他字段的字段边；
     * 一旦沿数据集边整体波及某下游，该下游数据集的全部字段边才参与传播。
     */
    public ImpactResult impactOfField(FieldRef source) {
        validateRef(source, "来源字段");
        lock.readLock().lock();
        try {
            return computeImpact(source.dataset(), source, false);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 统一影响遍历。三类工作项：
     * - 字段工作项：该字段被波及 → 登记其所属数据集为“字段触达”，沿字段边传播，
     *   并沿该数据集的数据集边把下游“整体波及”。
     * - 整体数据集工作项（wholesale=true）：经数据集边到达或为变更来源本身 →
     *   继续整体波及数据集边下游，并展开其全部字段边。
     * - 字段触达数据集工作项（wholesale=false）：仅被某字段触达 →
     *   只沿数据集边整体波及下游，不展开其内其他字段的字段边。
     */
    private ImpactResult computeImpact(String sourceDataset, FieldRef sourceField, boolean sourceWholesale) {
        Set<String> reachedDatasets = new LinkedHashSet<>();
        Set<FieldRef> reachedFields = new LinkedHashSet<>();
        /** 已按“字段触达”口径展开过数据集边的数据集，避免重复扫描。 */
        Set<String> touchedExpanded = new HashSet<>();
        Deque<Work> queue = new ArrayDeque<>();
        if (sourceWholesale) {
            queue.add(Work.dataset(sourceDataset, true));
        } else {
            queue.add(Work.field(sourceField));
        }
        while (!queue.isEmpty()) {
            Work work = queue.poll();
            if (work.isField) {
                FieldRef f = work.fieldRef;
                if (!reachedFields.add(f)) {
                    continue;
                }
                // 字段被波及 ⇒ 所属数据集记为字段触达；仅沿数据集边整体波及下游一次
                reachedDatasets.add(f.dataset());
                if (touchedExpanded.add(f.dataset())) {
                    enqueueDatasetDownstreams(queue, f.dataset(), reachedDatasets);
                }
                for (FieldRef target : fieldDownstream.getOrDefault(f, Set.of())) {
                    queue.add(Work.field(target));
                }
            } else if (work.wholesale) {
                String d = work.datasetName;
                // 整体波及：数据集边下游继续整体波及，并展开本数据集全部字段边
                enqueueDatasetDownstreams(queue, d, reachedDatasets);
                for (FieldRef target : fieldTargetsSourcedFrom(d)) {
                    queue.add(Work.field(target));
                }
            } else {
                String d = work.datasetName;
                // 仅字段触达：只沿数据集边整体波及，不株连其他字段
                enqueueDatasetDownstreams(queue, d, reachedDatasets);
            }
        }
        return buildImpact(reachedDatasets, reachedFields, sourceDataset);
    }

    /** 把数据集 d 的数据集级下游作为“整体波及”工作项入队（首次到达才入队）。 */
    private void enqueueDatasetDownstreams(Deque<Work> queue, String d, Set<String> reachedDatasets) {
        for (String nextDs : dsDownstream.getOrDefault(d, Set.of())) {
            if (reachedDatasets.add(nextDs)) {
                queue.add(Work.dataset(nextDs, true));
            }
        }
    }

    /** 某数据集的全部传递下游（不含自身），结果排序保证稳定。 */
    public Set<String> downstreamImpact(String dataset) {
        validateName(dataset, "数据集");
        lock.readLock().lock();
        try {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(dataset);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (String next : dsDownstream.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return new TreeSet<>(visited);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 某数据集的全部传递上游（不含自身），结果排序保证稳定。 */
    public Set<String> upstreamLineage(String dataset) {
        validateName(dataset, "数据集");
        lock.readLock().lock();
        try {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(dataset);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (String next : dsUpstream.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return new TreeSet<>(visited);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 观测 ====================

    /** 当前全部数据集级血缘边（排序，便于观测与测试）。 */
    public List<LineageEdge> listEdges() {
        lock.readLock().lock();
        try {
            List<LineageEdge> edges = new ArrayList<>();
            dsDownstream.forEach((up, downs) -> downs.forEach(down -> edges.add(new LineageEdge(up, down))));
            edges.sort(Comparator.comparing(LineageEdge::upstream).thenComparing(LineageEdge::downstream));
            return edges;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 当前全部字段级血缘边（排序，便于观测与测试）。 */
    public List<FieldLineageEdge> listFieldEdges() {
        lock.readLock().lock();
        try {
            List<FieldLineageEdge> edges = new ArrayList<>();
            fieldDownstream.forEach((up, downs) -> downs.forEach(down -> edges.add(new FieldLineageEdge(up, down))));
            edges.sort(Comparator.comparing(FieldLineageEdge::upstream).thenComparing(FieldLineageEdge::downstream));
            return edges;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 内部实现 ====================

    /** 源字段属于数据集 d 的全部字段边的目标字段。 */
    private Set<FieldRef> fieldTargetsSourcedFrom(String dataset) {
        Set<FieldRef> targets = new HashSet<>();
        fieldDownstream.forEach((source, downs) -> {
            if (source.dataset().equals(dataset)) {
                targets.addAll(downs);
            }
        });
        return targets;
    }

    /**
     * 成环检测用的统一图可达性（不含待写入的新边）。
     * 展开规则（把“字段归属数据集”也作为图上的连接参与判定）：
     * - 数据集节点 → 数据集级下游；本数据集在图中出现过的全部字段（归属展开）。
     * - 字段节点 → 字段级下游字段；其所属数据集的数据集级下游。
     * 命中条件：
     * - 新增数据集边 B -> A：从 A 出发，到达数据集 B 或 B 的任一字段即成环。
     * - 新增字段边 u -> v：从 v 出发，到达字段 u 即成环。
     *
     * @param virtualField 待写入字段边的上游字段；其数据集的归属展开需临时包含该新字段
     */
    private boolean reachesForCycle(Node start, String targetDataset, FieldRef targetField, FieldRef virtualField) {
        Set<Node> visited = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            List<Node> nexts = new ArrayList<>();
            if (node.dataset) {
                String d = node.datasetName;
                dsDownstream.getOrDefault(d, Set.of()).forEach(x -> nexts.add(Node.dataset(x)));
                knownFieldsByDataset.getOrDefault(d, Set.of())
                        .forEach(f -> nexts.add(Node.field(new FieldRef(d, f))));
                if (virtualField != null && virtualField.dataset().equals(d)) {
                    nexts.add(Node.field(virtualField));
                }
            } else {
                FieldRef f = node.fieldRef;
                fieldDownstream.getOrDefault(f, Set.of()).forEach(x -> nexts.add(Node.field(x)));
                dsDownstream.getOrDefault(f.dataset(), Set.of()).forEach(x -> nexts.add(Node.dataset(x)));
            }
            for (Node next : nexts) {
                if (targetField != null) {
                    if (!next.dataset && next.fieldRef.equals(targetField)) {
                        return true;
                    }
                } else {
                    if (next.dataset && next.datasetName.equals(targetDataset)) {
                        return true;
                    }
                    if (!next.dataset && next.fieldRef.dataset().equals(targetDataset)) {
                        return true;
                    }
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /** 汇总影响结果：排序去重，并剔除来源数据集自身及其字段。 */
    private ImpactResult buildImpact(Set<String> datasets, Set<FieldRef> fields, String sourceDataset) {
        List<String> dsList = new ArrayList<>(new TreeSet<>(datasets));
        dsList.remove(sourceDataset);
        List<FieldRef> fList = new ArrayList<>(new TreeSet<>(fields));
        fList.removeIf(f -> f.dataset().equals(sourceDataset));
        return new ImpactResult(List.copyOf(dsList), List.copyOf(fList));
    }

    private void validateRef(FieldRef ref, String role) {
        if (ref == null) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, role + "坐标不能为空");
        }
        validateName(ref.dataset(), role + "所属数据集");
        validateName(ref.field(), role + "名称");
    }

    private void validateName(String name, String role) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, role + "名称不能为空");
        }
    }

    private static String format(FieldRef ref) {
        return ref.dataset() + "." + ref.field();
    }

    /** 影响遍历工作项：字段，或数据集（wholesale=整体波及 / false=仅字段触达）。 */
    private record Work(boolean isField, FieldRef fieldRef, String datasetName, boolean wholesale) {
        static Work field(FieldRef ref) {
            return new Work(true, ref, null, false);
        }

        static Work dataset(String name, boolean wholesale) {
            return new Work(false, null, name, wholesale);
        }
    }

    /** 统一图节点：数据集节点或字段节点。 */
    private record Node(boolean dataset, String datasetName, FieldRef fieldRef) {
        static Node dataset(String name) {
            return new Node(true, name, null);
        }

        static Node field(FieldRef ref) {
            return new Node(false, null, ref);
        }
    }
}
