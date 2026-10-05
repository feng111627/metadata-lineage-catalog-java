package com.github.feng111627.metadatalineagecatalogjava.domain;

/**
 * 一个字段在某一数据集中的模式信息（不可变）。
 *
 * @param name          字段名
 * @param type          逻辑数据类型
 * @param nullable      是否允许为空
 * @param semantic      业务语义标识（如 user_id / email），语义变化视为破坏性变更
 * @param sensitivity   敏感级别标记
 * @param description   字段说明
 */
public record FieldSchema(
        String name,
        DataType type,
        boolean nullable,
        String semantic,
        SensitivityLevel sensitivity,
        String description
) {
    public FieldSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("field type must not be null");
        }
        if (sensitivity == null) {
            sensitivity = SensitivityLevel.NONE;
        }
        if (semantic == null) {
            semantic = "";
        }
        if (description == null) {
            description = "";
        }
    }
}
