package com.github.feng111627.metadatalineagecatalogjava.model;

/** 定位到具体字段的兼容性问题。 */
public record CompatibilityIssue(String fieldName, CompatibilityIssueType type, String detail) {
}
