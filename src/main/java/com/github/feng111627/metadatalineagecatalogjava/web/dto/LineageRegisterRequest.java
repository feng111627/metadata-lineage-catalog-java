package com.github.feng111627.metadatalineagecatalogjava.web.dto;

/**
 * 血缘边登记请求。field 为 null/空时表示数据集级血缘。
 */
public record LineageRegisterRequest(
        String upstreamDataset,
        String upstreamField,
        String downstreamDataset,
        String downstreamField,
        String createdBy
) {
}
