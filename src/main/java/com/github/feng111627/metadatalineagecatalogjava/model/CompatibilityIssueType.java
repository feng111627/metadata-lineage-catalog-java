package com.github.feng111627.metadatalineagecatalogjava.model;

/** 兼容性问题的可区分原因类型。 */
public enum CompatibilityIssueType {
    FIELD_REMOVED,
    TYPE_CHANGED_INCOMPATIBLE,
    SEMANTIC_CHANGED,
    SENSITIVITY_REMOVED,
    SENSITIVITY_CHANGED
}
