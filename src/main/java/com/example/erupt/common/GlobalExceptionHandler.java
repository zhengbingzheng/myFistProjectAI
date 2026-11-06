package com.example.erupt.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
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
        // 类级约束（@ScriptAssert 等）的错误不在 FieldErrors 里，一并收集，
        // 否则校验明明失败了却只剩兜底文案，查不出是哪条规则没过
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fieldError) {
                        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
                    }
                    return error.getDefaultMessage();
                })
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
     * 其余数据完整性冲突（数据超长、非空约束、CHECK 失败等）。
     *
     * <p>不接住会落进兜底变 500 并污染 ERROR 告警，实际是调用方数据不合法。
     * Spring 会优先命中上面更具体的 {@link DuplicateKeyException} 子类分支，409 语义不受影响。</p>
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("数据完整性冲突：{}", ex.getRootCause() != null ? ex.getRootCause().getMessage() : ex.getMessage());
        return badRequest(ResultCode.BAD_REQUEST, "数据不合法，违反完整性约束");
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
     * 必填的 @RequestParam / @PathVariable 缺失。
     *
     * <p>不接住会落进兜底变成 500——调用方拿到"服务内部异常"，且污染 ERROR 告警日志。</p>
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("缺少必填参数：{}", ex.getParameterName());
        return badRequest(ResultCode.BAD_REQUEST, "缺少必填参数：" + ex.getParameterName());
    }

    /**
     * Content-Type 与接口期望不符，例如对 @RequestBody JSON 端点提交表单。
     * 信封 code 与 HTTP 状态同为 415，与其余错误一一对应的口径保持一致。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("媒体类型不支持：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.failure(ResultCode.UNSUPPORTED_MEDIA_TYPE,
                        "Content-Type 不被支持：" + ex.getContentType()));
    }

    /**
     * 其余 multipart 异常（非尺寸类，如请求不是 multipart 却上传）。
     * 放在 MaxUploadSizeExceededException 之后，更具体的子类优先命中。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipartException(MultipartException ex) {
        log.warn("multipart 请求非法：{}", ex.getMessage());
        return badRequest(ResultCode.BAD_REQUEST, "上传请求非法，请使用 multipart/form-data 提交");
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

    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<Result<Void>> handleTaskRejected(TaskRejectedException ex) {
        log.warn("异步任务被拒绝：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Result.failure(ResultCode.TOO_MANY_REQUESTS, "当前请求较多，请稍后重试"));
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
