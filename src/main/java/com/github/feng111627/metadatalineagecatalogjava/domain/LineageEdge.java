package com.github.feng111627.metadatalineagecatalogjava.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条血缘依赖边：上游 -> 下游。
 * 数据集级血缘：两端 field 均为 null；字段级血缘：两端 field 均非空。
 *
 * <p>身份（去重键）只由四元组 (upstreamDataset, upstreamField,
 * downstreamDataset, downstreamField) 决定；createdAt / createdBy 仅为审计信息，
 * 不参与等值，因此重复提交同一条依赖不会产生第二条边。</p>
 */
public final class LineageEdge {

    private final String upstreamDataset;
    private final String upstreamField;
    private final String downstreamDataset;
    private final String downstreamField;
    private final Instant createdAt;
    private final String createdBy;

    @JsonCreator
    public LineageEdge(
            @JsonProperty("upstreamDataset") String upstreamDataset,
            @JsonProperty("upstreamField") String upstreamField,
            @JsonProperty("downstreamDataset") String downstreamDataset,
            @JsonProperty("downstreamField") String downstreamField,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("createdBy") String createdBy) {
        this.upstreamDataset = Objects.requireNonNull(upstreamDataset, "upstreamDataset");
        this.downstreamDataset = Objects.requireNonNull(downstreamDataset, "downstreamDataset");
        this.upstreamField = upstreamField;
        this.downstreamField = downstreamField;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.createdBy = createdBy == null ? "" : createdBy;
    }

    @JsonProperty("upstreamDataset")
    public String upstreamDataset() { return upstreamDataset; }

    @JsonProperty("upstreamField")
    public String upstreamField() { return upstreamField; }

    @JsonProperty("downstreamDataset")
    public String downstreamDataset() { return downstreamDataset; }

    @JsonProperty("downstreamField")
    public String downstreamField() { return downstreamField; }

    @JsonProperty("createdAt")
    public Instant createdAt() { return createdAt; }

    @JsonProperty("createdBy")
    public String createdBy() { return createdBy; }

    public boolean datasetLevel() {
        return upstreamField == null && downstreamField == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineageEdge e)) return false;
        return Objects.equals(upstreamDataset, e.upstreamDataset)
                && Objects.equals(upstreamField, e.upstreamField)
                && Objects.equals(downstreamDataset, e.downstreamDataset)
                && Objects.equals(downstreamField, e.downstreamField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(upstreamDataset, upstreamField, downstreamDataset, downstreamField);
    }

    @Override
    public String toString() {
        return endpoint(upstreamDataset, upstreamField) + " -> "
                + endpoint(downstreamDataset, downstreamField);
    }

    private static String endpoint(String dataset, String field) {
        return field == null ? dataset : dataset + "." + field;
    }
}
