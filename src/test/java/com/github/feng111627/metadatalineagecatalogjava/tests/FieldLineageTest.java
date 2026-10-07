package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldLineageEdge;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldRef;
import com.github.feng111627.metadatalineagecatalogjava.model.ImpactResult;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 业务：字段级血缘登记、跨粒度成环拒绝与字段影响分析。 */
@DisplayName("业务-字段级血缘与影响分析")
class FieldLineageTest {

    private final LineageService lineage = new LineageService();

    @Test
    @DisplayName("用例: 同一条字段依赖重复提交幂等，只保留一条")
    void duplicateFieldEdgeIsIdempotent() {
        for (int i = 0; i < 5; i++) {
            lineage.addFieldEdge("ods_order", "order_id", "dwd_order", "oid");
        }
        assertThat(lineage.listFieldEdges()).hasSize(1);
        assertThat(lineage.impactOfField("ods_order", "order_id").fields())
                .containsExactly(new FieldRef("dwd_order", "oid"));
    }

    @Test
    @DisplayName("用例: 字段-字段间接环路被拒绝，错误类型为 LINEAGE_CYCLE")
    void fieldOnlyCycleRejected() {
        lineage.addFieldEdge("a", "x", "b", "y");
        lineage.addFieldEdge("b", "y", "c", "z");
        assertThatThrownBy(() -> lineage.addFieldEdge("c", "z", "a", "x"))
                .isInstanceOfSatisfying(CatalogException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE);
                    assertThat(e.getMessage()).contains("c.z -> a.x");
                });
        // 拒绝后该边不在图中
        assertThat(lineage.listFieldEdges()).hasSize(2);
        assertThat(lineage.impactOfField("c", "z").datasets()).isEmpty();
    }

    @Test
    @DisplayName("用例: 同数据集内字段自依赖（含同字段自环）被拒绝")
    void sameDatasetFieldDependencyRejected() {
        assertThatThrownBy(() -> lineage.addFieldEdge("d", "f1", "d", "f2"))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
        assertThatThrownBy(() -> lineage.addFieldEdge("d", "f1", "d", "f1"))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
        assertThat(lineage.listFieldEdges()).isEmpty();
    }

    @Test
    @DisplayName("用例: 字段边与已有数据集边混合绕环被拒绝（跨粒度）")
    void crossGranularityCycleRejected() {
        // 显式数据集边：ads -> ods
        lineage.addEdge("ads_report", "ods_order");
        // 字段边隐含 ods -> ads，正好闭环：ods_order.x -> ads_report.r
        assertThatThrownBy(() -> lineage.addFieldEdge("ods_order", "x", "ads_report", "r"))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
        assertThat(lineage.listFieldEdges()).isEmpty();
    }

    @Test
    @DisplayName("用例: 数据集边与已有字段边混合绕环被拒绝（反向跨粒度）")
    void reverseCrossGranularityCycleRejected() {
        // 字段边隐含 ods -> dwd
        lineage.addFieldEdge("ods_order", "id", "dwd_order", "order_id");
        // 显式数据集边 dwd -> ods 会闭环
        assertThatThrownBy(() -> lineage.addEdge("dwd_order", "ods_order"))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
        assertThat(lineage.listEdges()).isEmpty();
    }

    @Test
    @DisplayName("用例: 字段影响分析返回下游数据集与下游字段，多路径不重复、排除来源")
    void fieldImpactTraversal() {
        // ods.x -> dwd.y -> ads.z；ods.x -> dws.q
        lineage.addFieldEdge("ods", "x", "dwd", "y");
        lineage.addFieldEdge("dwd", "y", "ads", "z");
        lineage.addFieldEdge("ods", "x", "dws", "q");
        ImpactResult impact = lineage.impactOfField("ods", "x");
        assertThat(impact.datasets()).containsExactly("ads", "dwd", "dws");
        assertThat(impact.fields()).containsExactly(
                new FieldRef("ads", "z"),
                new FieldRef("dwd", "y"),
                new FieldRef("dws", "q"));
        // 来源字段与来源数据集都不在结果中
        assertThat(impact.datasets()).doesNotContain("ods");
        assertThat(impact.fields()).doesNotContain(new FieldRef("ods", "x"));
    }

    @Test
    @DisplayName("用例: 菱形字段依赖（两路径汇入同一字段）只计一次")
    void diamondFieldImpactNoDuplicates() {
        lineage.addFieldEdge("a", "x", "b", "y1");
        lineage.addFieldEdge("a", "x", "b", "y2");
        lineage.addFieldEdge("b", "y1", "c", "z");
        lineage.addFieldEdge("b", "y2", "c", "z");
        ImpactResult impact = lineage.impactOfField("a", "x");
        assertThat(impact.datasets()).containsExactly("b", "c");
        assertThat(impact.fields()).containsExactly(
                new FieldRef("b", "y1"), new FieldRef("b", "y2"), new FieldRef("c", "z"));
    }

    @Test
    @DisplayName("用例: 相同输入多次影响分析结果内容与顺序完全一致（可复现）")
    void impactResultStableAcrossCalls() {
        lineage.addFieldEdge("src", "f", "mid", "g");
        lineage.addFieldEdge("mid", "g", "dst", "h");
        lineage.addFieldEdge("src", "f", "dst", "k");
        List<ImpactResult> results = List.of(
                lineage.impactOfField("src", "f"),
                lineage.impactOfField("src", "f"),
                lineage.impactOfField("src", "f"));
        assertThat(results).allSatisfy(r -> {
            assertThat(r.datasets()).containsExactly("dst", "mid");
            assertThat(r.fields()).containsExactly(
                    new FieldRef("dst", "h"), new FieldRef("dst", "k"), new FieldRef("mid", "g"));
        });
    }

    @Test
    @DisplayName("用例: 数据集影响分析合并字段边隐含依赖，返回受波及数据集与字段")
    void datasetImpactIncludesFieldImpliedEdges() {
        // 字段边隐含 ods -> dwd -> ads
        lineage.addFieldEdge("ods", "id", "dwd", "oid");
        lineage.addFieldEdge("dwd", "oid", "ads", "rid");
        // 显式数据集边 ods -> ext
        lineage.addEdge("ods", "ext");
        ImpactResult impact = lineage.impactOfDataset("ods");
        assertThat(impact.datasets()).containsExactly("ads", "dwd", "ext");
        assertThat(impact.fields()).containsExactly(
                new FieldRef("ads", "rid"), new FieldRef("dwd", "oid"));
        assertThat(impact.datasets()).doesNotContain("ods");
    }

    @Test
    @DisplayName("用例: 必填项缺失（字段名为空）返回 VALIDATION_ERROR，与成环错误可区分")
    void blankFieldNameIsValidationError() {
        assertThatThrownBy(() -> lineage.addFieldEdge("ods", "  ", "dwd", "x"))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
