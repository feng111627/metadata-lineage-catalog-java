package com.github.feng111627.metadatalineagecatalogjava.api;

import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;

/** 对外统一错误响应。 */
public record ErrorResponse(ErrorCode code, String message) {
}
