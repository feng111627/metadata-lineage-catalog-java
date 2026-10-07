package com.github.feng111627.metadatalineagecatalogjava.error;

/** 对外暴露的稳定错误类型。 */
public enum ErrorCode {
    DATASET_NOT_FOUND,
    VERSION_NOT_FOUND,
    VERSION_CONFLICT,
    INCOMPATIBLE_SCHEMA,
    SENSITIVITY_LOST,
    LINEAGE_CYCLE,
    VALIDATION_ERROR,
    REQUEST_BODY_MISSING,
    MALFORMED_JSON,
    INTERNAL_ERROR
}
