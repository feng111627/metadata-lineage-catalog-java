package com.github.feng111627.metadatalineagecatalogjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 数据集的一个不可变版本。
 *
 * @param datasetName 数据集名（逻辑主键）
 * @param version     从 1 起单调递增的版本号
 * @param fields      该版本下的字段模式（顺序即登记顺序）
 * @param createdAt   版本产生时间
 * @param createdBy   变更提交者
 * @param changeNote  变更说明
 * @param basedOn     基于哪个版本提交（乐观并发控制用）；首个版本为 0
 */
public record DatasetVersion(
        String datasetName,
        int version,
        List<FieldSchema> fields,
        Instant createdAt,
        String createdBy,
        String changeNote,
        int basedOn
) {
    public DatasetVersion {
        fields = List.copyOf(fields);
    }
}
