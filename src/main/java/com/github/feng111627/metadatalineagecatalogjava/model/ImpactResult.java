package com.github.feng111627.metadatalineagecatalogjava.model;

import java.util.List;

/**
 * 影响分析结果。
 * datasets：受波及的下游数据集（排序、去重、不含来源数据集）。
 * fields：受波及的下游字段（排序、去重、不含来源字段）。
 */
public record ImpactResult(List<String> datasets, List<FieldRef> fields) {
}
