package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.domain.DataType;
import com.github.feng111627.metadatalineagecatalogjava.domain.ImpactReport;
import com.github.feng111627.metadatalineagecatalogjava.domain.SensitivityLevel;
import com.github.feng111627.metadatalineagecatalogjava.engine.CompatibilityEngine;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.service.CatalogService;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import com.github.feng111627.metadatalineagecatalogjava.store.CatalogStore;
import com.github.feng111627.metadatalineagecatalogjava.tests.support.BusinessCase;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.DatasetRegisterRequest;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.LineageRegisterRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 血缘登记、重复提交、成环拒绝与影响分析测试。
 */
@BusinessCase("血缘登记与影响分析")
class LineageTests {

    private final CatalogStore store = new CatalogStore();
    private final CatalogService catalog = new CatalogService(store, new CompatibilityEngine());
    private final LineageService lineage = new LineageService(store);

    private void registerDataset(String name, String... fields) {
        List<DatasetRegisterRequest.FieldInput> inputs = java.util.Arrays.stream(fields)
                .map(f -> new DatasetRegisterRequest.FieldInput(f, DataType.STRING, true, "",
                        SensitivityLevel.NONE, ""))
                .toList();
        catalog.register(name, new DatasetRegisterRequest(0, "init", "t", inputs));
    }

    private LineageRegisterRequest edge(String up, String upF, String down, String downF) {
        return new LineageRegisterRequest(up, upF, down, downF, "t");
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "重复提交同一条依赖幂等，只保留一条边")
    void duplicateSubmissionIsIdempotent() {
        registerDataset("a", "id");
        registerDataset("b", "id");

        assertTrue(lineage.registerEdge(edge("a", "id", "b", "id")));
        boolean second = lineage.registerEdge(edge("a", "id", "b", "id"));

        assertFalse(second);
        assertEquals(1, lineage.edges().size());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "数据集级自依赖成环被明确拒绝")
    void selfDependencyIsRejected() {
        registerDataset("a");

        CatalogException ex = assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("a", null, "a", null)));

        assertEquals(CatalogErrorCode.LINEAGE_CYCLE, ex.errorCode());
        assertTrue(lineage.edges().isEmpty());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "数据集级间接环路被拒绝且不写入")
    void indirectDatasetCycleIsRejected() {
        registerDataset("a");
        registerDataset("b");
        registerDataset("c");

        lineage.registerEdge(edge("a", null, "b", null));
        lineage.registerEdge(edge("b", null, "c", null));

        CatalogException ex = assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("c", null, "a", null)));

        assertEquals(CatalogErrorCode.LINEAGE_CYCLE, ex.errorCode());
        assertEquals(2, lineage.edges().size());
        // 环边再次提交仍是同样的拒绝，不会静默写入
        assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("c", null, "a", null)));
        assertEquals(2, lineage.edges().size());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "字段级环路被拒绝")
    void fieldLevelCycleIsRejected() {
        registerDataset("a", "x");
        registerDataset("b", "y");

        lineage.registerEdge(edge("a", "x", "b", "y"));

        CatalogException ex = assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("b", "y", "a", "x")));
        assertEquals(CatalogErrorCode.LINEAGE_CYCLE, ex.errorCode());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "跨粒度依赖形成的环路也被拒绝")
    void crossGranularityCycleIsRejected() {
        registerDataset("a", "x");
        registerDataset("b", "y");

        // 数据集级 a -> b
        lineage.registerEdge(edge("a", null, "b", null));
        // 字段级 b.y -> a.x：经由数据集-字段成员关系构成环
        CatalogException ex = assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("b", "y", "a", "x")));

        assertEquals(CatalogErrorCode.LINEAGE_CYCLE, ex.errorCode());
        assertEquals(1, lineage.edges().size());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "影响分析返回全部可达下游并按跳数排序")
    void impactAnalysisFindsAllDownstream() {
        registerDataset("a");
        registerDataset("b");
        registerDataset("c");
        registerDataset("d");
        lineage.registerEdge(edge("a", null, "b", null));
        lineage.registerEdge(edge("b", null, "c", null));
        lineage.registerEdge(edge("a", null, "d", null));

        ImpactReport report = lineage.analyzeImpact("a", null);

        assertEquals(List.of("b", "d"),
                report.impacted().stream().filter(n -> n.distance() == 1).map(ImpactReport.ImpactedNode::dataset).sorted().toList());
        assertEquals(List.of("c"),
                report.impacted().stream().filter(n -> n.distance() == 2).map(ImpactReport.ImpactedNode::dataset).toList());
        assertEquals(3, report.impacted().size());
        // 源数据集自身（含其字段）不得出现在受影响下游中
        assertTrue(report.impacted().stream().noneMatch(n -> n.dataset().equals("a")));
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "跨粒度影响：字段边下游的数据集级节点也被波及且跳数正确")
    void crossGranularityImpactReachesDatasetNode() {
        registerDataset("a", "id");
        registerDataset("b", "id");
        registerDataset("c");
        lineage.registerEdge(edge("a", "id", "b", "id"));
        lineage.registerEdge(edge("b", null, "c", null));

        ImpactReport report = lineage.analyzeImpact("a", "id");

        assertEquals(List.of("b"), report.impacted().stream()
                .filter(n -> n.distance() == 1).map(ImpactReport.ImpactedNode::dataset).distinct().sorted().toList());
        assertTrue(report.impacted().stream().anyMatch(n ->
                n.dataset().equals("b") && n.field() == null && n.distance() == 1));
        assertTrue(report.impacted().stream().anyMatch(n ->
                n.dataset().equals("c") && n.distance() == 2));
        assertTrue(report.impacted().stream().noneMatch(n -> n.dataset().equals("a")));
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "无下游时影响报告为空")
    void impactAnalysisEmptyWhenLeaf() {
        registerDataset("a");

        ImpactReport report = lineage.analyzeImpact("a", null);

        assertTrue(report.impacted().isEmpty());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "引用未登记数据集返回稳定错误类型 LINEAGE_ENDPOINT_NOT_FOUND")
    void endpointNotFoundIsTypedError() {
        registerDataset("a");

        CatalogException ex = assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("a", null, "ghost", null)));

        assertEquals(CatalogErrorCode.LINEAGE_ENDPOINT_NOT_FOUND, ex.errorCode());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "引用不存在的字段被拒绝")
    void unknownFieldIsRejected() {
        registerDataset("a", "id");
        registerDataset("b", "id");

        CatalogException ex = assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("a", "missing", "b", "id")));

        assertEquals(CatalogErrorCode.LINEAGE_ENDPOINT_NOT_FOUND, ex.errorCode());
        assertTrue(lineage.edges().isEmpty());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "字段级与数据集级粒度不一致的边被拒绝")
    void mismatchedGranularityIsRejected() {
        registerDataset("a", "x");
        registerDataset("b");

        CatalogException ex = assertThrows(CatalogException.class,
                () -> lineage.registerEdge(edge("a", "x", "b", null)));

        assertEquals(CatalogErrorCode.INVALID_REQUEST, ex.errorCode());
    }

    @Test
    @BusinessCase(value = "血缘登记与影响分析", caseName = "字段变更可沿字段边传播到下游字段")
    void fieldImpactPropagatesThroughFieldEdges() {
        registerDataset("a", "id");
        registerDataset("b", "id");
        lineage.registerEdge(edge("a", "id", "b", "id"));

        ImpactReport report = lineage.analyzeImpact("a", "id");

        assertTrue(report.impacted().stream().anyMatch(n ->
                n.dataset().equals("b") && "id".equals(n.field()) && n.distance() == 1));
    }
}
