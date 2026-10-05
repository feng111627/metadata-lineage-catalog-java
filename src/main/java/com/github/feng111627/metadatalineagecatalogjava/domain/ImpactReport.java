package com.github.feng111627.metadatalineagecatalogjava.domain;

import java.util.List;

/**
 * 某个源节点发生变更时，沿血缘边传递得到的下游影响报告。
 *
 * @param originDataset 变更源数据集
 * @param originField   变更源字段；null 表示数据集级
 * @param impacted      可达的下游节点（按拓扑层级与名称稳定排序，不含源自身）
 * @param edgeCount     分析过程涉及的去重边数量
 */
public record ImpactReport(
        String originDataset,
        String originField,
        List<ImpactedNode> impacted,
        int edgeCount
) {
    public ImpactReport {
        impacted = List.copyOf(impacted);
    }

    /**
     * 受影响的下游节点。
     *
     * @param dataset    下游数据集
     * @param field      下游字段；null 表示整个数据集
     * @param distance   距变更源的跳数（从 1 开始）
     * @param via        到达该节点经过的上游节点描述
     */
    public record ImpactedNode(String dataset, String field, int distance, String via) {
        public String endpoint() {
            return field == null ? dataset : dataset + "." + field;
        }
    }
}
