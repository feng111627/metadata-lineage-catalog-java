package com.github.feng111627.metadatalineagecatalogjava.exception;

/**
 * 业务异常：只携带稳定错误码与可读信息，不向外部泄漏底层异常细节。
 */
public class CatalogException extends RuntimeException {

    private final CatalogErrorCode errorCode;

    public CatalogException(CatalogErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CatalogErrorCode errorCode() {
        return errorCode;
    }
}
