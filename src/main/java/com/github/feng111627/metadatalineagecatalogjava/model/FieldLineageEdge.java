package com.github.feng111627.metadatalineagecatalogjava.model;

/** 字段级血缘边：upstream 字段 -> downstream 字段。 */
public record FieldLineageEdge(FieldRef upstream, FieldRef downstream) {
}
