package com.github.feng111627.metadatalineagecatalogjava.service;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityIssue;
import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityIssueType;
import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityReport;
import com.github.feng111627.metadatalineagecatalogjava.model.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldMeta;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 数据集版本目录。
 * - 每个数据集维护一条单调递增的不可变版本链，历史版本一旦写入永不修改。
 * - 并发登记通过 per-dataset 锁 + 期望基线版本号实现乐观并发：
 *   调用方必须声明自己基于哪个版本修改，基线过期即拒绝，不会静默覆盖。
 */
@Service
public class DatasetCatalogService {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DatasetVersion>> versionsByDataset =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locksByDataset = new ConcurrentHashMap<>();
    private final CompatibilityChecker compatibilityChecker;

    public DatasetCatalogService(CompatibilityChecker compatibilityChecker) {
        this.compatibilityChecker = compatibilityChecker;
    }

    /** 登记全新数据集（版本 1）。已存在同名数据集时拒绝。 */
    public DatasetVersion registerDataset(String name, List<FieldMeta> fields) {
        validateName(name);
        validateFields(fields);
        ReentrantLock lock = locksByDataset.computeIfAbsent(name, k -> new ReentrantLock());
        lock.lock();
        try {
            CopyOnWriteArrayList<DatasetVersion> versions =
                    versionsByDataset.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>());
            if (!versions.isEmpty()) {
                throw new CatalogException(ErrorCode.VERSION_CONFLICT,
                        "数据集 [" + name + "] 已存在，不能重复登记初始版本");
            }
            DatasetVersion v = new DatasetVersion(name, 1, List.copyOf(fields), Instant.now());
            versions.add(v);
            return v;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 基于期望基线版本登记新版本。
     * 基线不是当前最新版本时抛 VERSION_CONFLICT；相邻版本不兼容时抛 INCOMPATIBLE_SCHEMA /
     * SENSITIVITY_LOST，并携带定位到字段的原因。
     */
    public DatasetVersion registerNewVersion(String name, int expectedBaseVersion, List<FieldMeta> fields) {
        validateName(name);
        validateFields(fields);
        ReentrantLock lock = locksByDataset.computeIfAbsent(name, k -> new ReentrantLock());
        lock.lock();
        try {
            CopyOnWriteArrayList<DatasetVersion> versions = versionsByDataset.get(name);
            if (versions == null || versions.isEmpty()) {
                throw new CatalogException(ErrorCode.DATASET_NOT_FOUND,
                        "数据集 [" + name + "] 不存在，请先登记初始版本");
            }
            DatasetVersion latest = versions.get(versions.size() - 1);
            if (latest.version() != expectedBaseVersion) {
                throw new CatalogException(ErrorCode.VERSION_CONFLICT,
                        "数据集 [" + name + "] 当前最新版本为 " + latest.version()
                                + "，与期望基线版本 " + expectedBaseVersion + " 不一致，存在并发修改，请刷新后重试");
            }
            DatasetVersion candidate = new DatasetVersion(name, latest.version() + 1,
                    List.copyOf(fields), Instant.now());
            CompatibilityReport report = compatibilityChecker.check(latest, candidate);
            if (!report.compatible()) {
                boolean sensitivityIssue = report.issues().stream().anyMatch(i ->
                        i.type() == CompatibilityIssueType.SENSITIVITY_REMOVED
                                || i.type() == CompatibilityIssueType.SENSITIVITY_CHANGED);
                String detail = report.issues().stream()
                        .map(i -> "字段[" + i.fieldName() + "] " + i.type() + ": " + i.detail())
                        .collect(Collectors.joining("; "));
                throw new CatalogException(
                        sensitivityIssue ? ErrorCode.SENSITIVITY_LOST : ErrorCode.INCOMPATIBLE_SCHEMA,
                        "数据集 [" + name + "] 版本 " + candidate.version() + " 与基线版本不兼容: " + detail);
            }
            versions.add(candidate);
            return candidate;
        } finally {
            lock.unlock();
        }
    }

    /** 查询指定历史版本。任何时刻返回同一不可变快照，结论一致。 */
    public DatasetVersion getVersion(String name, int version) {
        CopyOnWriteArrayList<DatasetVersion> versions = versionsByDataset.get(name);
        if (versions == null) {
            throw new CatalogException(ErrorCode.DATASET_NOT_FOUND, "数据集 [" + name + "] 不存在");
        }
        if (version < 1 || version > versions.size()) {
            throw new CatalogException(ErrorCode.VERSION_NOT_FOUND,
                    "数据集 [" + name + "] 不存在版本 " + version);
        }
        return versions.get(version - 1);
    }

    /** 判定相邻两个历史版本的兼容性（可复现：仅依赖两个快照内容）。 */
    public CompatibilityReport checkCompatibility(String name, int fromVersion, int toVersion) {
        if (toVersion != fromVersion + 1) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR,
                    "仅支持相邻版本兼容性判定: " + fromVersion + " -> " + toVersion);
        }
        return compatibilityChecker.check(getVersion(name, fromVersion), getVersion(name, toVersion));
    }

    public List<DatasetVersion> listVersions(String name) {
        CopyOnWriteArrayList<DatasetVersion> versions = versionsByDataset.get(name);
        if (versions == null) {
            throw new CatalogException(ErrorCode.DATASET_NOT_FOUND, "数据集 [" + name + "] 不存在");
        }
        return List.copyOf(versions);
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, "数据集名称不能为空");
        }
    }

    private void validateFields(List<FieldMeta> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, "字段列表不能为空");
        }
        long distinct = fields.stream().map(FieldMeta::name).distinct().count();
        if (distinct != fields.size()) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, "字段名不能重复");
        }
        for (FieldMeta f : fields) {
            if (f.name() == null || f.name().isBlank() || f.type() == null) {
                throw new CatalogException(ErrorCode.VALIDATION_ERROR, "字段名与类型不能为空");
            }
        }
    }
}
