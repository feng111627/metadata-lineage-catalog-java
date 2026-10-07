package com.github.feng111627.metadatalineagecatalogjava.model;

import java.util.List;

/**
 * 影响分析结果（已去重、按字典序排序）：
 * datasets = 被波及的下游数据集；fields = 被波及的下游具体字段。
 */
public record ImpactResult(List<String> datasets, List<FieldRef> fields) {
}
