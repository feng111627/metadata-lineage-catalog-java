package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 业务：血缘登记、成环拒绝与影响分析。 */
@DisplayName("业务-血缘与影响分析")
class LineageGraphTest {

    private final LineageService lineage = new LineageService();

    @Test
    @DisplayName("用例: 重复提交同一条依赖幂等，不产生重复边")
    void duplicateSubmissionIsIdempotent() {
        lineage.addEdge("ods_order", "dwd_order");
        lineage.addEdge("ods_order", "dwd_order");
        lineage.addEdge("ods_order", "dwd_order");
        assertThat(lineage.listEdges()).hasSize(1);
        assertThat(lineage.downstreamImpact("ods_order")).containsExactly("dwd_order");
    }

    @Test
    @DisplayName("用例: 直接自依赖被拒绝")
    void selfLoopRejected() {
        assertThatThrownBy(() -> lineage.addEdge("a", "a"))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
    }

    @Test
    @DisplayName("用例: 间接环路被拒绝且不静默写入")
    void indirectCycleRejected() {
        lineage.addEdge("a", "b");
        lineage.addEdge("b", "c");
        assertThatThrownBy(() -> lineage.addEdge("c", "a"))
                .isInstanceOfSatisfying(CatalogException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE);
                    assertThat(e.getMessage()).contains("c -> a");
                });
        // 拒绝后图中没有这条边
        assertThat(lineage.listEdges()).hasSize(2);
        assertThat(lineage.downstreamImpact("c")).isEmpty();
    }

    @Test
    @DisplayName("用例: 下游影响分析返回全部传递下游")
    void downstreamImpactAnalysis() {
        lineage.addEdge("ods_order", "dwd_order");
        lineage.addEdge("dwd_order", "dws_trade");
        lineage.addEdge("dwd_order", "dws_risk");
        lineage.addEdge("dws_trade", "ads_report");
        assertThat(lineage.downstreamImpact("ods_order"))
                .containsExactly("ads_report", "dwd_order", "dws_risk", "dws_trade");
        assertThat(lineage.downstreamImpact("dws_risk")).isEmpty();
    }

    @Test
    @DisplayName("用例: 上游血缘追溯返回全部传递上游")
    void upstreamLineageTrace() {
        lineage.addEdge("ods_order", "dwd_order");
        lineage.addEdge("ods_user", "dwd_order");
        lineage.addEdge("dwd_order", "ads_report");
        assertThat(lineage.upstreamLineage("ads_report"))
                .containsExactly("dwd_order", "ods_order", "ods_user");
    }

    @Test
    @DisplayName("用例: 菱形依赖（多路径）影响分析不重复计数")
    void diamondDependencyNoDuplicates() {
        lineage.addEdge("a", "b");
        lineage.addEdge("a", "c");
        lineage.addEdge("b", "d");
        lineage.addEdge("c", "d");
        assertThat(lineage.downstreamImpact("a")).containsExactly("b", "c", "d");
    }
}
