package com.example.erupt.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 全局异常处理：统一把异常映射为 {@link Result}，避免堆栈泄漏到前端。
 *
 * <p>重点覆盖两类此前会被误判为 500 的场景：
 * 请求体格式/类型错误（应为 400）与唯一约束冲突（应为 409）。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        log.warn("业务异常：{}", ex.getMessage());
        HttpStatus status = switch (ex.resultCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            // 下面三个此前会掉进 default 返回 500：语义上的"未登录""无权限""过于频繁"
            // 会被当成服务内部错误。后果不只是状态码难看——前端按 HTTP 401 判定未登录、
            // 按 403 判定无权限，全都匹配不上，鉴权分支形同失效。
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(Result.failure(ex.resultCode(), ex.getMessage()));
    }

    /**
     * @RequestBody 校验失败（DTO 内部约束）。
     */
    /**
     * 找不到任何处理器时（多数是请求了不存在的路径）返回 404。
     *
     * <p>Spring 会先按静态资源尝试解析，失败后抛 {@link NoResourceFoundException}。
     * 没有这条处理时它会落进最后的兜底分支变成 500——
     * 把一个"地址写错了"说成"服务端故障"，既误导调用方，也会污染 5xx 告警。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(ResultCode.NOT_FOUND, "接口不存在，请检查请求路径"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return badRequest(ResultCode.BAD_REQUEST, defaultIfBlank(message, "请求参数校验失败"));
    }

    /**
     * @RequestParam / @PathVariable 上的约束校验失败（类级别 @Validated）。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidationException(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", "));
        return badRequest(ResultCode.BAD_REQUEST, defaultIfBlank(message, "请求参数校验失败"));
    }

    /**
     * Bean Validation 直接抛出的约束违反。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        return badRequest(ResultCode.BAD_REQUEST, defaultIfBlank(message, "请求参数校验失败"));
    }

    /**
     * 路径/查询参数类型无法转换，例如 {@code /api/users/abc} 中的 id。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型不匹配：name={}, value={}", ex.getName(), ex.getValue());
        return badRequest(ResultCode.BAD_REQUEST, "参数 " + ex.getName() + " 类型不正确");
    }

    /**
     * 请求体缺失或 JSON 格式非法。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败：{}", ex.getMessage());
        return badRequest(ResultCode.BAD_REQUEST, "请求体格式不正确");
    }

    /**
     * 唯一索引冲突（如账号重复），并发场景下的兜底，避免暴露成 500。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKeyException(DuplicateKeyException ex) {
        log.warn("唯一约束冲突：{}", ex.getRootCause() != null ? ex.getRootCause().getMessage() : ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failure(ResultCode.CONFLICT, "数据已存在，违反唯一约束"));
    }

    /**
     * 附件上传超过容器层限制（spring.servlet.multipart）。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过服务端限制：{}", ex.getMessage());
        return badRequest(ResultCode.BAD_REQUEST, "上传文件超过服务端限制，请调整后重试");
    }

    /**
     * multipart 请求缺少必需的文件字段（如未提供 file / files）。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingServletRequestPartException(MissingServletRequestPartException ex) {
        log.warn("上传请求缺少文件字段：{}", ex.getRequestPartName());
        return badRequest(ResultCode.BAD_REQUEST, "缺少上传文件字段：" + ex.getRequestPartName());
    }

    /**
     * 请求方法不被支持，例如对只接收 POST 的上传接口发 GET。
     *
     * <p>不单独处理会落到下面的兜底分支，返回 500——把调用方的方法用错伪装成了服务端故障，
     * 排查时会被误导去翻服务端日志。这里明确回 405 并说明原因。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持：method={}, supported={}", ex.getMethod(), ex.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.failure(ResultCode.METHOD_NOT_ALLOWED,
                        "该接口不支持 " + ex.getMethod() + " 请求"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("未预期的系统异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ResultCode.INTERNAL_ERROR));
    }

    private ResponseEntity<Result<Void>> badRequest(ResultCode code, String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.failure(code, message));
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
