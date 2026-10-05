package com.github.feng111627.metadatalineagecatalogjava.exception;

import org.springframework.http.HttpStatus;

/**
 * 对外暴露的稳定、可区分错误类型。
 * 每个类型对应固定的错误码字符串与 HTTP 状态。
 */
public enum CatalogErrorCode {

    DATASET_NOT_FOUND("DATASET_NOT_FOUND", HttpStatus.NOT_FOUND),
    VERSION_NOT_FOUND("VERSION_NOT_FOUND", HttpStatus.NOT_FOUND),
    VERSION_CONFLICT("VERSION_CONFLICT", HttpStatus.CONFLICT),
    DUPLICATE_FIELD("DUPLICATE_FIELD", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST("INVALID_REQUEST", HttpStatus.BAD_REQUEST),
    LINEAGE_CYCLE("LINEAGE_CYCLE", HttpStatus.CONFLICT),
    LINEAGE_ENDPOINT_NOT_FOUND("LINEAGE_ENDPOINT_NOT_FOUND", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final HttpStatus httpStatus;

    CatalogErrorCode(String code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
