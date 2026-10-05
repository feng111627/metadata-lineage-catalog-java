package com.github.feng111627.metadatalineagecatalogjava.model;

import java.util.Set;

/** 字段元数据：名称、类型、语义描述、敏感标记。 */
public record FieldMeta(String name, FieldType type, String semantic, Set<SensitivityTag> sensitivityTags) {
}
