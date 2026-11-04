package com.example.erupt.common;

/**
 * 业务异常：携带明确的 {@link ResultCode}，由全局异常处理器映射为 HTTP 响应。
 */
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.message());
        this.resultCode = resultCode;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode resultCode() {
        return resultCode;
    }
}
