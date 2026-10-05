package com.github.feng111627.metadatalineagecatalogjava.service;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.LineageEdge;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 血缘登记与影响分析。
 * - 边以集合形式存储，重复提交同一条依赖幂等，不产生重复结果。
 * - 写入前做环路检测，会形成环路的依赖直接拒绝，不静默写入。
 * - 影响分析返回某数据集的全部传递下游（排序后返回，结果稳定）。
 */
@Service
public class LineageService {

    /** downstream -> 其直接 upstream 集合；upstream -> 其直接 downstream 集合，两个视图保持一致。 */
    private final Map<String, Set<String>> upstreamsOf = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> downstreamsOf = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();

    /** 登记一条血缘边。重复提交幂等；成环抛 LINEAGE_CYCLE。 */
    public LineageEdge addEdge(String upstream, String downstream) {
        validateEndpoint(upstream, "上游");
        validateEndpoint(downstream, "下游");
        if (upstream.equals(downstream)) {
            throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                    "不允许数据集 [" + upstream + "] 依赖自身");
        }
        writeLock.lock();
        try {
            Set<String> existing = downstreamsOf.get(upstream);
            if (existing != null && existing.contains(downstream)) {
                return new LineageEdge(upstream, downstream); // 幂等：重复提交直接返回
            }
            // 若 downstream 的传递下游能到达 upstream，则加入该边会成环
            Set<String> reachable = downstreamImpact(downstream);
            if (reachable.contains(upstream)) {
                throw new CatalogException(ErrorCode.LINEAGE_CYCLE,
                        "依赖 " + upstream + " -> " + downstream + " 会形成环路（"
                                + downstream + " 的下游已可达 " + upstream + "），已拒绝写入");
            }
            downstreamsOf.computeIfAbsent(upstream, k -> ConcurrentHashMap.newKeySet()).add(downstream);
            upstreamsOf.computeIfAbsent(downstream, k -> ConcurrentHashMap.newKeySet()).add(upstream);
            return new LineageEdge(upstream, downstream);
        } finally {
            writeLock.unlock();
        }
    }

    /** 某数据集的全部传递下游（不含自身），结果排序保证稳定。 */
    public Set<String> downstreamImpact(String dataset) {
        validateEndpoint(dataset, "数据集");
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(dataset);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : downstreamsOf.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return new TreeSet<>(visited);
    }

    /** 某数据集的全部传递上游（不含自身），结果排序保证稳定。 */
    public Set<String> upstreamLineage(String dataset) {
        validateEndpoint(dataset, "数据集");
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(dataset);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : upstreamsOf.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return new TreeSet<>(visited);
    }

    /** 当前全部血缘边（排序，便于观测与测试）。 */
    public List<LineageEdge> listEdges() {
        List<LineageEdge> edges = new ArrayList<>();
        downstreamsOf.forEach((up, downs) ->
                downs.forEach(down -> edges.add(new LineageEdge(up, down))));
        edges.sort(java.util.Comparator.comparing(LineageEdge::upstream)
                .thenComparing(LineageEdge::downstream));
        return edges;
    }

    private void validateEndpoint(String name, String role) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, role + "数据集名称不能为空");
        }
    }
}
