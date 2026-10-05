package com.github.feng111627.metadatalineagecatalogjava.error;

/** 目录服务统一业务异常，携带稳定错误码。 */
public class CatalogException extends RuntimeException {
    private final ErrorCode code;

    public CatalogException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
