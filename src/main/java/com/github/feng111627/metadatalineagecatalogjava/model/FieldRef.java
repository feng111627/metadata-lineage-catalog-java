package com.github.feng111627.metadatalineagecatalogjava.model;

/** 字段引用：定位“某个数据集里的某个字段”。 */
public record FieldRef(String dataset, String field) {
}
