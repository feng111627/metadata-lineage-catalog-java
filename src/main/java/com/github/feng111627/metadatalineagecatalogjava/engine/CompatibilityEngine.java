package com.github.feng111627.metadatalineagecatalogjava.engine;

import com.github.feng111627.metadatalineagecatalogjava.domain.ChangeReason;
import com.github.feng111627.metadatalineagecatalogjava.domain.CompatibilityResult;
import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.domain.FieldChange;
import com.github.feng111627.metadatalineagecatalogjava.domain.FieldSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 相邻版本兼容性判定引擎。
 *
 * <p>判定规则（对同一字段名逐项检查，结论只取决于两个版本的内容，因此稳定可复现）：</p>
 * <ul>
 *   <li>{@link ChangeReason#FIELD_ADDED}：新版本新增字段 —— 兼容（非阻断）。</li>
 *   <li>{@link ChangeReason#FIELD_REMOVED}：字段被删除 —— 不兼容（阻断）。</li>
 *   <li>{@link ChangeReason#TYPE_CHANGED}：逻辑类型改变 —— 不兼容（阻断）。</li>
 *   <li>{@link ChangeReason#NULLABILITY_TIGHTENED}：可空收窄为非空 —— 不兼容（阻断）。</li>
 *   <li>{@link ChangeReason#SEMANTIC_CHANGED}：业务语义标识改变 —— 不兼容（阻断）。</li>
 *   <li>{@link ChangeReason#SENSITIVITY_DOWNGRADED}：敏感级别下调或移除 —— 不兼容（阻断），
 *       不允许把已有敏感标记悄悄降级。</li>
 *   <li>{@link ChangeReason#SENSITIVITY_UPGRADED}：敏感级别上调 —— 兼容，但给出提示性变更。</li>
 * </ul>
 *
 * <p>首个版本登记（from 为 null）时，所有字段视为新增，结论恒为兼容。</p>
 */
@Component
public class CompatibilityEngine {

    /**
     * 比较两个相邻版本。
     *
     * @param from 旧版本；null 表示首次登记
     * @param to   新版本
     * @return 按字段名稳定排序的差异列表与兼容结论
     */
    public CompatibilityResult compare(DatasetVersion from, DatasetVersion to) {
        int fromVersion = from == null ? 0 : from.version();
        if (from == null) {
            List<FieldChange> changes = to.fields().stream()
                    .map(f -> FieldChange.of(ChangeReason.FIELD_ADDED, f.name(),
                            "field introduced with type " + f.type(), false))
                    .sorted((a, b) -> a.fieldName().compareTo(b.fieldName()))
                    .toList();
            return new CompatibilityResult(true, 0, to.version(), changes);
        }

        Map<String, FieldSchema> oldFields = indexByName(from);
        Map<String, FieldSchema> newFields = indexByName(to);

        TreeSet<String> allNames = new TreeSet<>();
        allNames.addAll(oldFields.keySet());
        allNames.addAll(newFields.keySet());

        List<FieldChange> changes = new ArrayList<>();
        for (String name : allNames) {
            FieldSchema oldField = oldFields.get(name);
            FieldSchema newField = newFields.get(name);

            if (oldField == null) {
                changes.add(FieldChange.of(ChangeReason.FIELD_ADDED, name,
                        "new field of type " + newField.type()
                                + (newField.sensitivity().ordinal() > 0
                                        ? ", sensitivity=" + newField.sensitivity()
                                        : ""),
                        false));
                continue;
            }
            if (newField == null) {
                changes.add(FieldChange.of(ChangeReason.FIELD_REMOVED, name,
                        "field removed (was type " + oldField.type() + ")", true));
                continue;
            }

            if (newField.type() != oldField.type()) {
                changes.add(FieldChange.of(ChangeReason.TYPE_CHANGED, name,
                        "type " + oldField.type() + " -> " + newField.type(), true));
            }
            if (oldField.nullable() && !newField.nullable()) {
                changes.add(FieldChange.of(ChangeReason.NULLABILITY_TIGHTENED, name,
                        "nullable true -> false", true));
            }
            if (!normalize(oldField.semantic()).equals(normalize(newField.semantic()))) {
                changes.add(FieldChange.of(ChangeReason.SEMANTIC_CHANGED, name,
                        "semantic '" + normalize(oldField.semantic()) + "' -> '"
                                + normalize(newField.semantic()) + "'", true));
            }
            if (newField.sensitivity().ordinal() < oldField.sensitivity().ordinal()) {
                changes.add(FieldChange.of(ChangeReason.SENSITIVITY_DOWNGRADED, name,
                        "sensitivity " + oldField.sensitivity() + " -> " + newField.sensitivity(),
                        true));
            } else if (newField.sensitivity().ordinal() > oldField.sensitivity().ordinal()) {
                changes.add(FieldChange.of(ChangeReason.SENSITIVITY_UPGRADED, name,
                        "sensitivity " + oldField.sensitivity() + " -> " + newField.sensitivity(),
                        false));
            }
        }

        boolean compatible = changes.stream().noneMatch(FieldChange::blocking);
        return new CompatibilityResult(compatible, fromVersion, to.version(), changes);
    }

    private static Map<String, FieldSchema> indexByName(DatasetVersion version) {
        Map<String, FieldSchema> map = new LinkedHashMap<>();
        for (FieldSchema field : version.fields()) {
            map.put(field.name(), field);
        }
        return map;
    }

    private static String normalize(String semantic) {
        return semantic == null ? "" : semantic.trim();
    }
}
