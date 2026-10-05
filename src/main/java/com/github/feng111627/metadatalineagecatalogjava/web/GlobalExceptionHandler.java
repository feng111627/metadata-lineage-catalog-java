package com.github.feng111627.metadatalineagecatalogjava.web;

import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：对外只暴露稳定错误码与可读信息，底层异常不原样抛出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ApiError> handleCatalog(CatalogException ex) {
        CatalogErrorCode code = ex.errorCode();
        return ResponseEntity.status(code.httpStatus())
                .body(new ApiError(code.code(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(CatalogErrorCode.INVALID_REQUEST.code(),
                        "malformed or unreadable request body"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(CatalogErrorCode.INVALID_REQUEST.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        // 未预期异常仅记录日志，对外返回固定类型，避免泄漏堆栈或底层细节
        log.error("unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(CatalogErrorCode.INTERNAL_ERROR.code(),
                        "an unexpected internal error occurred"));
    }
}
