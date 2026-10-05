package com.github.feng111627.metadatalineagecatalogjava.web.dto;

import com.github.feng111627.metadatalineagecatalogjava.domain.CompatibilityResult;
import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;

/**
 * 一次版本登记的结果：新版本本身 + 与相邻旧版本的兼容性结论。
 */
public record RegistrationOutcome(
        DatasetVersion version,
        CompatibilityResult compatibility
) {
}
