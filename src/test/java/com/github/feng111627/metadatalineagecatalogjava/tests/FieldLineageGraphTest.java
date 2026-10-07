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
class FieldLineageGraphTest {

    private final LineageService lineage = new LineageService();

    private FieldRef ref(String dataset, String field) {
        return new FieldRef(dataset, field);
    }

    @Test
    @DisplayName("用例: 同一条字段依赖重复提交多次只算一条，不产生重复记录")
    void duplicateFieldEdgeIsIdempotent() {
        FieldRef up = ref("ods_order", "order_id");
        FieldRef down = ref("dwd_order", "oid");
        for (int i = 0; i < 5; i++) {
            lineage.addFieldEdge(up, down);
        }
        assertThat(lineage.listFieldEdges()).hasSize(1);
        assertThat(lineage.listFieldEdges().get(0)).isEqualTo(new FieldLineageEdge(up, down));
    }

    @Test
    @DisplayName("用例: 字段直接自依赖被拒绝且错误类型稳定为 LINEAGE_CYCLE")
    void fieldSelfLoopRejected() {
        FieldRef f = ref("a", "id");
        assertThatThrownBy(() -> lineage.addFieldEdge(f, f))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
        assertThat(lineage.listFieldEdges()).isEmpty();
    }

    @Test
    @DisplayName("用例: 字段之间绕成的间接环被拒绝且不静默写入")
    void fieldOnlyIndirectCycleRejected() {
        lineage.addFieldEdge(ref("a", "x"), ref("b", "y"));
        lineage.addFieldEdge(ref("b", "y"), ref("c", "z"));
        assertThatThrownBy(() -> lineage.addFieldEdge(ref("c", "z"), ref("a", "x")))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
        assertThat(lineage.listFieldEdges()).hasSize(2);
    }

    @Test
    @DisplayName("用例: 字段边与已有数据集级依赖跨粒度绕环被拒绝（数据集边先登记，环由新字段边闭合）")
    void crossGranularityCycleViaFieldEdgeRejected() {
        // 数据集级已有 A -> B；登记 B.b -> A.a 会闭合混合环
        lineage.addEdge("a", "b");
        assertThatThrownBy(() -> lineage.addFieldEdge(ref("b", "f"), ref("a", "f")))
                .isInstanceOfSatisfying(CatalogException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE);
                    assertThat(e.getMessage()).contains("a.f").contains("b.f");
                });
        assertThat(lineage.listFieldEdges()).isEmpty();
        // 数据集级边保持不变
        assertThat(lineage.listEdges()).hasSize(1);
    }

    @Test
    @DisplayName("用例: 数据集边与已有字段级依赖跨粒度绕环被拒绝（字段边先登记，环由新数据集边闭合）")
    void crossGranularityCycleViaDatasetEdgeRejected() {
        // 字段级已有 B.b -> A.a；再登记数据集级 A -> B 会闭合混合环
        lineage.addFieldEdge(ref("b", "f"), ref("a", "f"));
        assertThatThrownBy(() -> lineage.addEdge("a", "b"))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LINEAGE_CYCLE));
        assertThat(lineage.listEdges()).isEmpty();
        assertThat(lineage.listFieldEdges()).hasSize(1);
    }

    @Test
    @DisplayName("用例: 同数据集内的字段依赖不属于跨粒度环，正常登记")
    void intraDatasetFieldEdgeAllowed() {
        lineage.addFieldEdge(ref("a", "raw"), ref("a", "clean"));
        assertThat(lineage.listFieldEdges()).hasSize(1);
    }

    @Test
    @DisplayName("用例: 同数据集字段链不会因与本数据集的数据集级边组合而被误判成环")
    void intraDatasetChainNotMistakenAsCycle() {
        // B 整体依赖 A（A -> B），B 内部 raw -> clean 的加工链合法
        lineage.addEdge("a", "b");
        lineage.addFieldEdge(ref("b", "raw"), ref("b", "clean"));
        lineage.addFieldEdge(ref("b", "clean"), ref("b", "ads"));
        assertThat(lineage.listFieldEdges()).hasSize(2);
    }

    @Test
    @DisplayName("用例: 字段影响分析沿字段边传播且结果按 dataset/field 字典序稳定可复现")
    void fieldImpactStableOrderAndReproducible() {
        // a.id -> b.oid, a.name -> b.oname, b.oid -> c.cid, b.oname -> c.cname
        lineage.addFieldEdge(ref("a", "id"), ref("b", "oid"));
        lineage.addFieldEdge(ref("a", "name"), ref("b", "oname"));
        lineage.addFieldEdge(ref("b", "oid"), ref("c", "cid"));
        lineage.addFieldEdge(ref("b", "oname"), ref("c", "cname"));
        ImpactResult first = lineage.impactOfField(ref("a", "id"));
        ImpactResult second = lineage.impactOfField(ref("a", "id"));
        assertThat(first).isEqualTo(second);
        assertThat(first.fields())
                .containsExactly(ref("b", "oid"), ref("c", "cid"));
        // 从 a 出发，b、c 均被波及
        assertThat(first.datasets()).containsExactly("b", "c");
    }

    @Test
    @DisplayName("用例: 字段影响分析排除来源自身数据集与其全部字段，且无重复项")
    void fieldImpactExcludesSourceAndNoDuplicates() {
        // 菱形：a.id -> b.x, a.id -> b.y, b.x -> c.z, b.y -> c.z
        lineage.addFieldEdge(ref("a", "id"), ref("b", "x"));
        lineage.addFieldEdge(ref("a", "id"), ref("b", "y"));
        lineage.addFieldEdge(ref("b", "x"), ref("c", "z"));
        lineage.addFieldEdge(ref("b", "y"), ref("c", "z"));
        ImpactResult impact = lineage.impactOfField(ref("a", "id"));
        assertThat(impact.fields())
                .doesNotContain(ref("a", "id"))
                .containsExactly(ref("b", "x"), ref("b", "y"), ref("c", "z"));
        assertThat(impact.datasets()).doesNotContain("a").containsExactly("b", "c");
    }

    @Test
    @DisplayName("用例: 数据集影响分析同时给出被波及的下游数据集与具体下游字段")
    void datasetImpactIncludesDatasetsAndFields() {
        lineage.addEdge("a", "b");
        lineage.addFieldEdge(ref("a", "id"), ref("c", "cid"));
        lineage.addFieldEdge(ref("c", "cid"), ref("d", "did"));
        ImpactResult impact = lineage.impactOfDataset("a");
        // b 由数据集边整体波及；c、d 由字段边链波及
        assertThat(impact.datasets()).containsExactly("b", "c", "d");
        assertThat(impact.fields()).containsExactly(ref("c", "cid"), ref("d", "did"));
        // 来源自身排除
        assertThat(impact.datasets()).doesNotContain("a");
    }

    @Test
    @DisplayName("用例: 被字段波及的数据集会沿其数据集级边继续整体向下游传播")
    void impactedFieldPropagatesThroughDatasetEdges() {
        // a.x -> b.y，且 b 整体 -> d
        lineage.addFieldEdge(ref("a", "x"), ref("b", "y"));
        lineage.addEdge("b", "d");
        ImpactResult impact = lineage.impactOfField(ref("a", "x"));
        assertThat(impact.fields()).containsExactly(ref("b", "y"));
        assertThat(impact.datasets()).containsExactly("b", "d");
    }

    @Test
    @DisplayName("用例: 无下游时影响结果为空且来源不出现")
    void noDownstreamImpactIsEmpty() {
        lineage.addFieldEdge(ref("a", "x"), ref("b", "y"));
        ImpactResult impact = lineage.impactOfField(ref("b", "y"));
        assertThat(impact.datasets()).isEmpty();
        assertThat(impact.fields()).isEmpty();
    }

    @Test
    @DisplayName("用例: 字段坐标缺失/空白以 VALIDATION_ERROR 拒绝")
    void blankFieldRefRejected() {
        assertThatThrownBy(() -> lineage.addFieldEdge(null, ref("b", "y")))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> lineage.addFieldEdge(ref("a", "  "), ref("b", "y")))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
