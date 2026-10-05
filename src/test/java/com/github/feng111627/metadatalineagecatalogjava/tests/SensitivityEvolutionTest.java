package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldMeta;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldType;
import com.github.feng111627.metadatalineagecatalogjava.model.SensitivityTag;
import com.github.feng111627.metadatalineagecatalogjava.service.CompatibilityChecker;
import com.github.feng111627.metadatalineagecatalogjava.service.DatasetCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 业务：敏感标记随版本演进与追溯。 */
@DisplayName("业务-敏感标记演进")
class SensitivityEvolutionTest {

    private final DatasetCatalogService catalog = new DatasetCatalogService(new CompatibilityChecker());

    private FieldMeta field(String name, SensitivityTag... tags) {
        return new FieldMeta(name, FieldType.STRING, name + "-语义", Set.of(tags));
    }

    @Test
    @DisplayName("用例: 新增敏感标记放行且随版本保留可追溯")
    void addingTagIsAllowedAndTraceable() {
        catalog.registerDataset("users", List.of(field("name")));
        DatasetVersion v2 = catalog.registerNewVersion("users", 1, List.of(field("name", SensitivityTag.PII)));
        assertThat(v2.fields().get(0).sensitivityTags()).containsExactly(SensitivityTag.PII);
        // 历史版本快照不受新版本影响，随时可查
        assertThat(catalog.getVersion("users", 1).fields().get(0).sensitivityTags()).isEmpty();
        assertThat(catalog.getVersion("users", 2).fields().get(0).sensitivityTags())
                .containsExactly(SensitivityTag.PII);
    }

    @Test
    @DisplayName("用例: 丢失已有敏感标记被拒绝并定位到字段")
    void losingTagIsRejected() {
        catalog.registerDataset("users", List.of(field("phone", SensitivityTag.PII)));
        assertThatThrownBy(() -> catalog.registerNewVersion("users", 1, List.of(field("phone"))))
                .isInstanceOfSatisfying(CatalogException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.SENSITIVITY_LOST);
                    assertThat(e.getMessage()).contains("phone").contains("SENSITIVITY");
                });
        // 拒绝后版本链不被污染
        assertThat(catalog.listVersions("users")).hasSize(1);
    }

    @Test
    @DisplayName("用例: 替换敏感标记（PII 改 SECRET）被拒绝并给出可区分原因")
    void replacingTagIsRejectedWithDistinctReason() {
        catalog.registerDataset("users", List.of(field("id_card", SensitivityTag.PII)));
        assertThatThrownBy(() -> catalog.registerNewVersion("users", 1,
                List.of(field("id_card", SensitivityTag.SECRET))))
                .isInstanceOfSatisfying(CatalogException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.SENSITIVITY_LOST);
                    assertThat(e.getMessage()).contains("SENSITIVITY_CHANGED");
                });
    }

    @Test
    @DisplayName("用例: 多字段混合演进时只放行全部合规的版本")
    void mixedEvolution() {
        catalog.registerDataset("orders", List.of(
                field("buyer", SensitivityTag.PII),
                field("amount")));
        // buyer 丢标记 -> 拒绝
        assertThatThrownBy(() -> catalog.registerNewVersion("orders", 1, List.of(
                field("buyer"),
                field("amount", SensitivityTag.FINANCIAL))))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SENSITIVITY_LOST));
        // 全部保留并新增 -> 放行
        DatasetVersion v2 = catalog.registerNewVersion("orders", 1, List.of(
                field("buyer", SensitivityTag.PII),
                field("amount", SensitivityTag.FINANCIAL)));
        assertThat(v2.version()).isEqualTo(2);
    }
}
