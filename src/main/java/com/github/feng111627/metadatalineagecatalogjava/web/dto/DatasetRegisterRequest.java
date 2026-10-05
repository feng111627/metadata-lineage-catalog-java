package com.github.feng111627.metadatalineagecatalogjava.web.dto;

import com.github.feng111627.metadatalineagecatalogjava.domain.DataType;
import com.github.feng111627.metadatalineagecatalogjava.domain.SensitivityLevel;

import java.util.List;

/**
 * 数据集新版本登记请求。
 *
 * @param expectedVersion 提交所基于的当前版本号；首次登记为 0
 * @param changeNote      变更说明
 * @param createdBy       提交者
 * @param fields          新版本的完整字段列表
 */
public record DatasetRegisterRequest(
        Integer expectedVersion,
        String changeNote,
        String createdBy,
        List<FieldInput> fields
) {
    /**
     * 字段输入。
     */
    public record FieldInput(
            String name,
            DataType type,
            Boolean nullable,
            String semantic,
            SensitivityLevel sensitivity,
            String description
    ) {
    }
}
