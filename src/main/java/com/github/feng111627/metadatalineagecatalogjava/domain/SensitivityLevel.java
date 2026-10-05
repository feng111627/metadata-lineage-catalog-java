package com.github.feng111627.metadatalineagecatalogjava.domain;

/**
 * 字段敏感级别（从低到高）。
 * NONE 表示未标记敏感属性。
 */
public enum SensitivityLevel {
    NONE,
    INTERNAL,
    CONFIDENTIAL,
    PII
}
