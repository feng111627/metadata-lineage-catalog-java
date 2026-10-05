package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityIssueType;
import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityReport;
import com.github.feng111627.metadatalineagecatalogjava.model.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldMeta;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldType;
import com.github.feng111627.metadatalineagecatalogjava.service.CompatibilityChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 业务：版本兼容性判定边界。 */
@DisplayName("业务-版本兼容性判定")
class CompatibilityBoundaryTest {

    private final CompatibilityChecker checker = new CompatibilityChecker();

    private DatasetVersion version(List<FieldMeta> fields) {
        return new DatasetVersion("orders", 1, fields, Instant.now());
    }

    private FieldMeta field(String name, FieldType type) {
        return new FieldMeta(name, type, name + "-语义", Set.of());
    }

    @Test
    @DisplayName("用例: 完全相同的 schema 兼容")
    void identicalIsCompatible() {
        CompatibilityReport report = checker.check(
                version(List.of(field("id", FieldType.LONG))),
                version(List.of(field("id", FieldType.LONG))));
        assertThat(report.compatible()).isTrue();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    @DisplayName("用例: 新增字段兼容")
    void addFieldIsCompatible() {
        CompatibilityReport report = checker.check(
                version(List.of(field("id", FieldType.LONG))),
                version(List.of(field("id", FieldType.LONG), field("note", FieldType.STRING))));
        assertThat(report.compatible()).isTrue();
    }

    @Test
    @DisplayName("用例: 删除字段不兼容并定位到字段")
    void removeFieldIsIncompatible() {
        CompatibilityReport report = checker.check(
                version(List.of(field("id", FieldType.LONG), field("amount", FieldType.DOUBLE))),
                version(List.of(field("id", FieldType.LONG))));
        assertThat(report.compatible()).isFalse();
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.fieldName()).isEqualTo("amount");
            assertThat(i.type()).isEqualTo(CompatibilityIssueType.FIELD_REMOVED);
        });
    }

    @Test
    @DisplayName("用例: 类型加宽 INT->LONG 兼容")
    void wideningIntToLongIsCompatible() {
        CompatibilityReport report = checker.check(
                version(List.of(field("qty", FieldType.INT))),
                version(List.of(field("qty", FieldType.LONG))));
        assertThat(report.compatible()).isTrue();
    }

    @Test
    @DisplayName("用例: 类型收窄 LONG->INT 不兼容并给出类型原因")
    void narrowingLongToIntIsIncompatible() {
        CompatibilityReport report = checker.check(
                version(List.of(field("qty", FieldType.LONG))),
                version(List.of(field("qty", FieldType.INT))));
        assertThat(report.compatible()).isFalse();
        assertThat(report.issues()).singleElement().satisfies(i -> {
            assertThat(i.fieldName()).isEqualTo("qty");
            assertThat(i.type()).isEqualTo(CompatibilityIssueType.TYPE_CHANGED_INCOMPATIBLE);
            assertThat(i.detail()).contains("LONG").contains("INT");
        });
    }

    @Test
    @DisplayName("用例: 语义描述变化不兼容")
    void semanticChangeIsIncompatible() {
        CompatibilityReport report = checker.check(
                version(List.of(new FieldMeta("status", FieldType.STRING, "订单状态", Set.of()))),
                version(List.of(new FieldMeta("status", FieldType.STRING, "物流状态", Set.of()))));
        assertThat(report.compatible()).isFalse();
        assertThat(report.issues()).singleElement().satisfies(i ->
                assertThat(i.type()).isEqualTo(CompatibilityIssueType.SEMANTIC_CHANGED));
    }

    @Test
    @DisplayName("用例: 同一输入重复判定结论一致（可复现）")
    void resultIsReproducible() {
        DatasetVersion v1 = version(List.of(field("a", FieldType.LONG), field("b", FieldType.STRING)));
        DatasetVersion v2 = version(List.of(field("a", FieldType.INT)));
        CompatibilityReport r1 = checker.check(v1, v2);
        CompatibilityReport r2 = checker.check(v1, v2);
        assertThat(r1).isEqualTo(r2);
    }
}
