package com.github.feng111627.metadatalineagecatalogjava.api;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射：业务异常映射为稳定错误码 + 合适 HTTP 状态；
 * 未知异常兜底为 INTERNAL_ERROR，不把底层异常原样抛出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ErrorResponse> handleCatalog(CatalogException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case DATASET_NOT_FOUND, VERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case INCOMPATIBLE_SCHEMA, SENSITIVITY_LOST, LINEAGE_CYCLE, VALIDATION_ERROR ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case REQUEST_BODY_MISSING, MALFORMED_JSON -> HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    /**
     * 请求体无法读取：区分“请求体缺失”与“JSON 格式错误”两种稳定错误码；
     * 不把 Jackson/底层解析异常原文外抛。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        String reason = ex.getMessage() == null ? "" : ex.getMessage();
        if (reason.contains("request body is missing") || reason.contains("Required request body is absent")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(ErrorCode.REQUEST_BODY_MISSING,
                            "请求体缺失：该接口要求提交 JSON 请求体"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.MALFORMED_JSON,
                        "请求体不是合法的 JSON，请检查格式后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("未预期的内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR, "内部错误，请联系管理员"));
    }
}
