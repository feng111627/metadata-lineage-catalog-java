package com.github.feng111627.metadatalineagecatalogjava.model;

/** 数据集级血缘边：upstream -> downstream。 */
public record LineageEdge(String upstream, String downstream) {
}
