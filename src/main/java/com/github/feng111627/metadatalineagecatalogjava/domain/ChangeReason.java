package com.github.feng111627.metadatalineagecatalogjava.domain;

/**
 * 单字段相邻版本差异的可区分原因。
 */
public enum ChangeReason {
    /** 字段为新增 */
    FIELD_ADDED,
    /** 字段被删除 */
    FIELD_REMOVED,
    /** 字段类型改变 */
    TYPE_CHANGED,
    /** 可空 -> 非空，收窄约束 */
    NULLABILITY_TIGHTENED,
    /** 业务语义标识改变 */
    SEMANTIC_CHANGED,
    /** 敏感级别被移除或下调 */
    SENSITIVITY_DOWNGRADED,
    /** 敏感级别上调（提示性，不阻断） */
    SENSITIVITY_UPGRADED
}
