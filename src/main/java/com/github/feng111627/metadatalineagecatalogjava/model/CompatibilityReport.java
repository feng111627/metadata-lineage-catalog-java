package com.github.feng111627.metadatalineagecatalogjava.model;

import java.util.List;

/** 相邻版本兼容性判定结论。 */
public record CompatibilityReport(boolean compatible, List<CompatibilityIssue> issues) {
}
