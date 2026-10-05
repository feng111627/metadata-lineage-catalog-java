package com.github.feng111627.metadatalineagecatalogjava.model;

import java.time.Instant;
import java.util.List;

/** 数据集某一版本的不可变快照。 */
public record DatasetVersion(String datasetName, int version, List<FieldMeta> fields, Instant createdAt) {
}
