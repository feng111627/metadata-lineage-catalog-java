package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.domain.ChangeReason;
import com.github.feng111627.metadatalineagecatalogjava.domain.CompatibilityResult;
import com.github.feng111627.metadatalineagecatalogjava.domain.DataType;
import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.domain.FieldChange;
import com.github.feng111627.metadatalineagecatalogjava.domain.FieldSchema;
import com.github.feng111627.metadatalineagecatalogjava.domain.SensitivityLevel;
import com.github.feng111627.metadatalineagecatalogjava.engine.CompatibilityEngine;
import com.github.feng111627.metadatalineagecatalogjava.tests.support.BusinessCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本兼容性边界测试。
 */
@BusinessCase("版本兼容性边界")
class CompatibilityBoundaryTests {

    private final CompatibilityEngine engine = new CompatibilityEngine();

    private static DatasetVersion version(int v, FieldSchema... fields) {
        return new DatasetVersion("ds", v, List.of(fields), Instant.now(), "tester", "n", v - 1);
    }

    private static FieldSchema field(String name, DataType type) {
        return new FieldSchema(name, type, true, "", SensitivityLevel.NONE, "");
    }

    private static FieldSchema field(String name, DataType type, boolean nullable, String semantic) {
        return new FieldSchema(name, type, nullable, semantic, SensitivityLevel.NONE, "");
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "首次登记所有字段视为新增且兼容")
    void firstVersionAllFieldsAddedAndCompatible() {
        CompatibilityResult result = engine.compare(null, version(1,
                field("id", DataType.BIGINT), field("name", DataType.STRING)));

        assertTrue(result.compatible());
        assertEquals(2, result.changes().size());
        assertTrue(result.changes().stream().allMatch(c -> c.reason() == ChangeReason.FIELD_ADDED));
        // 差异按字段名稳定排序
        assertEquals("id", result.changes().get(0).fieldName());
        assertEquals("name", result.changes().get(1).fieldName());
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "新增字段判定为兼容")
    void addingFieldIsCompatible() {
        var v1 = version(1, field("id", DataType.BIGINT));
        var v2 = version(2, field("id", DataType.BIGINT), field("remark", DataType.STRING));

        CompatibilityResult result = engine.compare(v1, v2);

        assertTrue(result.compatible());
        FieldChange added = result.changes().get(0);
        assertEquals(ChangeReason.FIELD_ADDED, added.reason());
        assertFalse(added.blocking());
        assertEquals("remark", added.fieldName());
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "删除字段判定为不兼容并定位到 FIELD_REMOVED")
    void removingFieldIsBlocking() {
        var v1 = version(1, field("id", DataType.BIGINT), field("ssn", DataType.STRING));
        var v2 = version(2, field("id", DataType.BIGINT));

        CompatibilityResult result = engine.compare(v1, v2);

        assertFalse(result.compatible());
        FieldChange removed = result.blockingChanges().get(0);
        assertEquals(ChangeReason.FIELD_REMOVED, removed.reason());
        assertEquals("ssn", removed.fieldName());
        assertTrue(removed.detail().contains("removed"));
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "字段改类型判定为 TYPE_CHANGED 不兼容")
    void typeChangeIsBlocking() {
        var v1 = version(1, field("amount", DataType.DECIMAL));
        var v2 = version(2, field("amount", DataType.STRING));

        CompatibilityResult result = engine.compare(v1, v2);

        assertFalse(result.compatible());
        FieldChange change = result.blockingChanges().get(0);
        assertEquals(ChangeReason.TYPE_CHANGED, change.reason());
        assertEquals("amount", change.fieldName());
        assertTrue(change.detail().contains("DECIMAL -> STRING"));
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "可空收窄为非空判定不兼容")
    void nullabilityTightenedIsBlocking() {
        var v1 = version(1, field("nickname", DataType.STRING, true, ""));
        var v2 = version(2, field("nickname", DataType.STRING, false, ""));

        CompatibilityResult result = engine.compare(v1, v2);

        assertFalse(result.compatible());
        assertEquals(ChangeReason.NULLABILITY_TIGHTENED, result.blockingChanges().get(0).reason());
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "非空放宽为可空仍然兼容")
    void nullabilityLoosenedIsCompatible() {
        var v1 = version(1, field("nickname", DataType.STRING, false, ""));
        var v2 = version(2, field("nickname", DataType.STRING, true, ""));

        CompatibilityResult result = engine.compare(v1, v2);

        assertTrue(result.compatible());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "业务语义改变判定 SEMANTIC_CHANGED 不兼容")
    void semanticChangeIsBlocking() {
        var v1 = version(1, field("code", DataType.STRING, true, "user_id"));
        var v2 = version(2, field("code", DataType.STRING, true, "device_id"));

        CompatibilityResult result = engine.compare(v1, v2);

        assertFalse(result.compatible());
        FieldChange change = result.blockingChanges().get(0);
        assertEquals(ChangeReason.SEMANTIC_CHANGED, change.reason());
        assertEquals("code", change.fieldName());
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "无任何变化时兼容且无差异")
    void noChangesIsCompatible() {
        var v1 = version(1, field("id", DataType.BIGINT));
        var v2 = version(2, field("id", DataType.BIGINT));

        CompatibilityResult result = engine.compare(v1, v2);

        assertTrue(result.compatible());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "多个破坏性差异全部报告并按字段名排序")
    void multipleBlockingChangesAllReported() {
        var v1 = version(1,
                field("a", DataType.STRING),
                field("b", DataType.STRING),
                field("c", DataType.STRING));
        var v2 = version(2,
                field("a", DataType.INTEGER),
                field("c", DataType.STRING)); // b 被删除

        CompatibilityResult result = engine.compare(v1, v2);

        assertFalse(result.compatible());
        assertEquals(2, result.blockingChanges().size());
        assertEquals("a", result.blockingChanges().get(0).fieldName());
        assertEquals("b", result.blockingChanges().get(1).fieldName());
        assertEquals(ChangeReason.TYPE_CHANGED, result.blockingChanges().get(0).reason());
        assertEquals(ChangeReason.FIELD_REMOVED, result.blockingChanges().get(1).reason());
    }

    @Test
    @BusinessCase(value = "版本兼容性边界", caseName = "同样输入重复判定结论完全一致（可复现）")
    void sameInputProducesSameConclusion() {
        var v1 = version(1, field("a", DataType.STRING), field("b", DataType.STRING));
        var v2 = version(2, field("a", DataType.INTEGER), field("b", DataType.STRING));

        CompatibilityResult first = engine.compare(v1, v2);
        CompatibilityResult second = engine.compare(v1, v2);

        assertEquals(first, second);
    }
}
