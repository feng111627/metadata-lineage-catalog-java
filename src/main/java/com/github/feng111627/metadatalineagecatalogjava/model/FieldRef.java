package com.github.feng111627.metadatalineagecatalogjava.model;

/**
 * 字段坐标：定位“某个数据集里的某一个字段”。
 * 自然顺序按 dataset、field 排序，保证影响分析结果顺序稳定可复现。
 */
public record FieldRef(String dataset, String field) implements Comparable<FieldRef> {

    @Override
    public int compareTo(FieldRef o) {
        int byDataset = this.dataset.compareTo(o.dataset);
        return byDataset != 0 ? byDataset : this.field.compareTo(o.field);
    }
}
