package com.github.feng111627.metadatalineagecatalogjava.api;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常映射：业务异常映射为稳定错误码 + 合适 HTTP 状态；
 * 请求层错误（缺请求体、JSON 非法、必填缺失、参数类型不符）各自映射为可区分的稳定错误码；
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

    /** 请求体缺失与 JSON 解析失败都表现为本异常，按原因区分为两个稳定错误码。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        String reason = ex.getMessage() == null ? "" : ex.getMessage();
        if (reason.startsWith("Required request body is missing")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(ErrorCode.REQUEST_BODY_MISSING, "请求体缺失：该接口要求携带 JSON 请求体"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.MALFORMED_JSON, "请求体不是合法的 JSON，请检查格式后重试"));
    }

    /** Bean Validation 校验失败（如 @NotBlank 缺失）：必填项缺失，422。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> "字段[" + fe.getField() + "] " + fe.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR, detail));
    }

    /** 路径/查询参数类型无法转换（如版本号传成文字）：422，不外抛底层原文。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ErrorCode.VALIDATION_ERROR,
                        "参数 [" + ex.getName() + "] 类型不合法"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("未预期的内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR, "内部错误，请联系管理员"));
    }
}
