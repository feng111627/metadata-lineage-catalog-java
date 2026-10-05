package com.github.feng111627.metadatalineagecatalogjava.service;

import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityIssue;
import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityIssueType;
import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityReport;
import com.github.feng111627.metadatalineagecatalogjava.model.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldMeta;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldType;
import com.github.feng111627.metadatalineagecatalogjava.model.SensitivityTag;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 相邻版本兼容性判定。
 * 规则（稳定、可复现，仅依赖两个版本快照内容）：
 * 1. 字段删除：不兼容。
 * 2. 类型变更：仅允许固定的加宽方向（INT→LONG→DOUBLE，DATE→TIMESTAMP，任意→STRING），其余不兼容。
 * 3. 语义描述变化：不兼容。
 * 4. 敏感标记：只允许新增；丢失或替换已有标记不兼容。
 * 5. 字段新增：兼容。
 */
@Component
public class CompatibilityChecker {

    /** 类型加宽白名单：from -> 允许演化到的类型集合。 */
    private static final Map<FieldType, Set<FieldType>> WIDENING = Map.of(
            FieldType.INT, Set.of(FieldType.LONG, FieldType.DOUBLE, FieldType.STRING),
            FieldType.LONG, Set.of(FieldType.DOUBLE, FieldType.STRING),
            FieldType.DOUBLE, Set.of(FieldType.STRING),
            FieldType.DATE, Set.of(FieldType.TIMESTAMP, FieldType.STRING),
            FieldType.TIMESTAMP, Set.of(FieldType.STRING),
            FieldType.BOOLEAN, Set.of(FieldType.STRING),
            FieldType.STRING, Set.of()
    );

    public CompatibilityReport check(DatasetVersion previous, DatasetVersion next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        Map<String, FieldMeta> nextFields = next.fields().stream()
                .collect(Collectors.toMap(FieldMeta::name, Function.identity(), (a, b) -> a));
        List<CompatibilityIssue> issues = new ArrayList<>();
        for (FieldMeta oldField : previous.fields()) {
            FieldMeta newField = nextFields.get(oldField.name());
            if (newField == null) {
                issues.add(new CompatibilityIssue(oldField.name(),
                        CompatibilityIssueType.FIELD_REMOVED,
                        "字段在新版本中被删除"));
                continue;
            }
            if (oldField.type() != newField.type()
                    && !WIDENING.getOrDefault(oldField.type(), Set.of()).contains(newField.type())) {
                issues.add(new CompatibilityIssue(oldField.name(),
                        CompatibilityIssueType.TYPE_CHANGED_INCOMPATIBLE,
                        "类型由 " + oldField.type() + " 变为 " + newField.type() + "，不在允许的类型加宽方向内"));
            }
            if (!Objects.equals(oldField.semantic(), newField.semantic())) {
                issues.add(new CompatibilityIssue(oldField.name(),
                        CompatibilityIssueType.SEMANTIC_CHANGED,
                        "语义描述由 [" + oldField.semantic() + "] 变为 [" + newField.semantic() + "]"));
            }
            Set<SensitivityTag> oldTags = oldField.sensitivityTags() == null ? Set.of() : oldField.sensitivityTags();
            Set<SensitivityTag> newTags = newField.sensitivityTags() == null ? Set.of() : newField.sensitivityTags();
            if (!newTags.containsAll(oldTags)) {
                Set<SensitivityTag> lost = oldTags.stream().filter(t -> !newTags.contains(t))
                        .collect(Collectors.toCollection(java.util.TreeSet::new));
                boolean replaced = !newTags.isEmpty();
                issues.add(new CompatibilityIssue(oldField.name(),
                        replaced ? CompatibilityIssueType.SENSITIVITY_CHANGED
                                 : CompatibilityIssueType.SENSITIVITY_REMOVED,
                        "敏感标记 " + lost + " 在新版本中丢失或被替换为 " + newTags));
            }
        }
        // 结果按字段名排序，保证结论稳定可复现
        issues.sort(java.util.Comparator.comparing(CompatibilityIssue::fieldName)
                .thenComparing(i -> i.type().name()));
        return new CompatibilityReport(issues.isEmpty(), List.copyOf(issues));
    }
}
