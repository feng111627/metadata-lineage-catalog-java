package com.github.feng111627.metadatalineagecatalogjava.store;

import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.domain.FieldSchema;
import com.github.feng111627.metadatalineagecatalogjava.domain.LineageEdge;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地内存存储。
 *
 * <p>版本按数据集分桶，每个桶有独立锁：提交是“检查基准版本 -> 分配新版本号 -> 追加”的
 * 原子操作，保证同一数据集的并发提交不会被静默覆盖；版本对象一经写入即不可变，
 * 任何时刻按版本号读到的结论都一致。</p>
 *
 * <p>血缘边以 {@link LineageEdge#equals(Object)} 为去重键存放在并发集合中，
 * 重复提交不会产生第二条边。</p>
 */
@Component
public class CatalogStore {

    private final ConcurrentMap<String, DatasetAggregate> datasets = new ConcurrentHashMap<>();

    private final Set<LineageEdge> edges = ConcurrentHashMap.newKeySet();

    /**
     * 原子提交新版本。
     *
     * @param datasetName  数据集名
     * @param expectedBase 调用方所基于的版本号（首次登记必须为 0）
     * @param fields       新版本的完整字段模式
     * @param createdBy    提交者
     * @param changeNote   变更说明
     * @return 已提交的不可变新版本
     * @throws CatalogException 当 expectedBase 与当前最新版本不一致时（VERSION_CONFLICT）
     */
    public DatasetVersion commitVersion(String datasetName, int expectedBase,
                                        List<FieldSchema> fields,
                                        String createdBy, String changeNote) {
        DatasetAggregate agg = datasets.computeIfAbsent(datasetName, k -> new DatasetAggregate());
        synchronized (agg.lock) {
            int current = agg.versions.isEmpty() ? 0 : agg.versions.get(agg.versions.size() - 1).version();
            if (current != expectedBase) {
                throw new CatalogException(CatalogErrorCode.VERSION_CONFLICT,
                        "dataset '" + datasetName + "' expected base version " + current
                                + " but request based on " + expectedBase);
            }
            int newVersion = current + 1;
            DatasetVersion committed = new DatasetVersion(
                    datasetName, newVersion, List.copyOf(fields),
                    Instant.now(), createdBy == null ? "" : createdBy,
                    changeNote == null ? "" : changeNote, expectedBase);
            agg.versions.add(committed);
            return committed;
        }
    }

    public Optional<DatasetVersion> latestVersion(String datasetName) {
        DatasetAggregate agg = datasets.get(datasetName);
        if (agg == null) {
            return Optional.empty();
        }
        synchronized (agg.lock) {
            return agg.versions.isEmpty()
                    ? Optional.empty()
                    : Optional.of(agg.versions.get(agg.versions.size() - 1));
        }
    }

    public Optional<DatasetVersion> getVersion(String datasetName, int version) {
        DatasetAggregate agg = datasets.get(datasetName);
        if (agg == null) {
            return Optional.empty();
        }
        synchronized (agg.lock) {
            return agg.versions.stream().filter(v -> v.version() == version).findFirst();
        }
    }

    public List<DatasetVersion> history(String datasetName) {
        DatasetAggregate agg = datasets.get(datasetName);
        if (agg == null) {
            return List.of();
        }
        synchronized (agg.lock) {
            return List.copyOf(agg.versions);
        }
    }

    /** 返回所有数据集名，按字典序稳定排序。 */
    public List<String> datasets() {
        return datasets.keySet().stream().sorted().toList();
    }

    /**
     * 原子加入血缘边。
     *
     * @return true 表示新登记；false 表示同样的边已存在（幂等）
     */
    public boolean addEdgeIfAbsent(LineageEdge edge) {
        return edges.add(edge);
    }

    public List<LineageEdge> edges() {
        return List.copyOf(edges);
    }

    /** 单个数据集的版本桶，versions 始终按版本号递增。 */
    private static final class DatasetAggregate {
        private final Object lock = new Object();
        private final List<DatasetVersion> versions = new ArrayList<>();
    }
}
