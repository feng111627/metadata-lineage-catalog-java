package com.github.feng111627.metadatalineagecatalogjava.domain;

/**
 * 针对单个字段的一条差异说明。
 *
 * @param reason     差异原因（稳定可区分的类型）
 * @param fieldName  字段名
 * @param detail     人类可读的细节（旧值 -> 新值）
 * @param blocking   是否构成不兼容（阻断）
 */
public record FieldChange(
        ChangeReason reason,
        String fieldName,
        String detail,
        boolean blocking
) {
    public static FieldChange of(ChangeReason reason, String fieldName, String detail, boolean blocking) {
        return new FieldChange(reason, fieldName, detail, blocking);
    }
}
