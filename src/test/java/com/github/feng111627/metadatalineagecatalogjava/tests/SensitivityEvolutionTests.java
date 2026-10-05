package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.domain.ChangeReason;
import com.github.feng111627.metadatalineagecatalogjava.domain.CompatibilityResult;
import com.github.feng111627.metadatalineagecatalogjava.domain.DataType;
import com.github.feng111627.metadatalineagecatalogjava.domain.SensitivityLevel;
import com.github.feng111627.metadatalineagecatalogjava.engine.CompatibilityEngine;
import com.github.feng111627.metadatalineagecatalogjava.store.CatalogStore;
import com.github.feng111627.metadatalineagecatalogjava.service.CatalogService;
import com.github.feng111627.metadatalineagecatalogjava.tests.support.BusinessCase;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.DatasetRegisterRequest;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.RegistrationOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感属性标记随版本演进的测试。
 */
@BusinessCase("敏感标记演进")
class SensitivityEvolutionTests {

    private CatalogService newService() {
        return new CatalogService(new CatalogStore(), new CompatibilityEngine());
    }

    private static DatasetRegisterRequest.FieldInput field(String name, SensitivityLevel level) {
        return new DatasetRegisterRequest.FieldInput(name, DataType.STRING, true, "", level, "");
    }

    @Test
    @BusinessCase(value = "敏感标记演进", caseName = "敏感标记随版本保留：历史版本与新版本都可追溯")
    void sensitivityPersistsAcrossVersions() {
        CatalogService service = newService();
        service.register("ds", new DatasetRegisterRequest(0, "v1", "t",
                List.of(field("email", SensitivityLevel.PII))));
        RegistrationOutcome v2 = service.register("ds", new DatasetRegisterRequest(1, "v2", "t",
                List.of(field("email", SensitivityLevel.PII), field("remark", SensitivityLevel.NONE))));

        assertEquals(2, v2.version().version());
        assertEquals(SensitivityLevel.PII,
                v2.version().fields().stream().filter(f -> f.name().equals("email")).findFirst().orElseThrow().sensitivity());
        // 历史版本 v1 结论不变
        assertEquals(SensitivityLevel.PII,
                service.versionAt("ds", 1).fields().stream().filter(f -> f.name().equals("email"))
                        .findFirst().orElseThrow().sensitivity());
    }

    @Test
    @BusinessCase(value = "敏感标记演进", caseName = "丢掉已有敏感标记被判定不兼容（SENSITIVITY_DOWNGRADED）")
    void droppingSensitivityIsBlocking() {
        CatalogService service = newService();
        service.register("ds", new DatasetRegisterRequest(0, "v1", "t",
                List.of(field("email", SensitivityLevel.CONFIDENTIAL))));
        RegistrationOutcome v2 = service.register("ds", new DatasetRegisterRequest(1, "v2", "t",
                List.of(field("email", SensitivityLevel.NONE))));

        assertFalse(v2.compatibility().compatible());
        var change = v2.compatibility().blockingChanges().get(0);
        assertEquals(ChangeReason.SENSITIVITY_DOWNGRADED, change.reason());
        assertEquals("email", change.fieldName());
        assertTrue(change.detail().contains("CONFIDENTIAL -> NONE"));
        // 版本仍然登记成功，由调用方依据兼容结论决定是否发布
        assertEquals(2, service.latest("ds").version());
    }

    @Test
    @BusinessCase(value = "敏感标记演进", caseName = "敏感级别下调被判定不兼容")
    void downgradingSensitivityIsBlocking() {
        CatalogService service = newService();
        service.register("ds", new DatasetRegisterRequest(0, "v1", "t",
                List.of(field("phone", SensitivityLevel.PII))));
        RegistrationOutcome v2 = service.register("ds", new DatasetRegisterRequest(1, "v2", "t",
                List.of(field("phone", SensitivityLevel.INTERNAL))));

        assertFalse(v2.compatibility().compatible());
        assertEquals(ChangeReason.SENSITIVITY_DOWNGRADED,
                v2.compatibility().blockingChanges().get(0).reason());
    }

    @Test
    @BusinessCase(value = "敏感标记演进", caseName = "敏感级别上调兼容但给出非阻断提示")
    void upgradingSensitivityIsWarnedButCompatible() {
        CatalogService service = newService();
        service.register("ds", new DatasetRegisterRequest(0, "v1", "t",
                List.of(field("name", SensitivityLevel.INTERNAL))));
        RegistrationOutcome v2 = service.register("ds", new DatasetRegisterRequest(1, "v2", "t",
                List.of(field("name", SensitivityLevel.PII))));

        CompatibilityResult compatibility = v2.compatibility();
        assertTrue(compatibility.compatible());
        assertEquals(1, compatibility.changes().size());
        var change = compatibility.changes().get(0);
        assertEquals(ChangeReason.SENSITIVITY_UPGRADED, change.reason());
        assertFalse(change.blocking());
    }

    @Test
    @BusinessCase(value = "敏感标记演进", caseName = "预演接口可提前看到敏感标记降级风险且不写版本")
    void previewShowsDowngradeWithoutWriting() {
        CatalogService service = newService();
        service.register("ds", new DatasetRegisterRequest(0, "v1", "t",
                List.of(field("email", SensitivityLevel.PII))));

        CompatibilityResult preview = service.previewCompatibility("ds",
                new DatasetRegisterRequest(1, "draft", "t", List.of(field("email", SensitivityLevel.NONE))));

        assertFalse(preview.compatible());
        assertEquals(ChangeReason.SENSITIVITY_DOWNGRADED,
                preview.blockingChanges().get(0).reason());
        // 预演不产生新版本
        assertEquals(1, service.latest("ds").version());
    }

    @Test
    @BusinessCase(value = "敏感标记演进", caseName = "敏感标记与删字段同时发生时两个原因都被报告")
    void downgradeAndRemovalBothReported() {
        CatalogService service = newService();
        service.register("ds", new DatasetRegisterRequest(0, "v1", "t",
                List.of(field("email", SensitivityLevel.PII), field("bio", SensitivityLevel.CONFIDENTIAL))));
        RegistrationOutcome v2 = service.register("ds", new DatasetRegisterRequest(1, "v2", "t",
                List.of(field("email", SensitivityLevel.NONE)))); // bio 删除

        var reasons = v2.compatibility().blockingChanges().stream()
                .map(c -> c.reason()).toList();
        assertTrue(reasons.contains(ChangeReason.SENSITIVITY_DOWNGRADED));
        assertTrue(reasons.contains(ChangeReason.FIELD_REMOVED));
    }
}
