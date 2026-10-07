package com.github.feng111627.metadatalineagecatalogjava.model;

/** 字段级血缘边：(upstreamDataset.upstreamField) -> (downstreamDataset.downstreamField)。 */
public record FieldLineageEdge(String upstreamDataset,
                               String upstreamField,
                               String downstreamDataset,
                               String downstreamField) {

    public FieldRef upstream() {
        return new FieldRef(upstreamDataset, upstreamField);
    }

    public FieldRef downstream() {
        return new FieldRef(downstreamDataset, downstreamField);
    }
}
