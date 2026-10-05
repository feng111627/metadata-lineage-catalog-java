package com.github.feng111627.metadatalineagecatalogjava.domain;

import java.util.List;

/**
 * 相邻两个版本之间的兼容性判定结论（不可变、可复现）。
 *
 * @param compatible    是否向后兼容
 * @param fromVersion   旧版本号（首个版本登记时为 0）
 * @param toVersion     新版本号
 * @param changes       逐字段差异（顺序稳定：按字段名排序）
 */
public record CompatibilityResult(
        boolean compatible,
        int fromVersion,
        int toVersion,
        List<FieldChange> changes
) {
    public CompatibilityResult {
        changes = List.copyOf(changes);
    }

    public List<FieldChange> blockingChanges() {
        return changes.stream().filter(FieldChange::blocking).toList();
    }
}
