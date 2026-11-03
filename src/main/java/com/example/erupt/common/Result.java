package com.example.erupt.common;

/**
 * 统一 REST 响应结构。
 *
 * @param code     业务状态码（见 {@link ResultCode}）
 * @param message  提示信息
 * @param data     业务数据
 * @param timestamp 响应时间戳（毫秒）
 */
public record Result<T>(int code, String message, T data, long timestamp) {

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.message(), data, System.currentTimeMillis());
    }

    public static <T> Result<T> success(T data, String message) {
        return new Result<>(ResultCode.SUCCESS.code(), message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> failure(ResultCode resultCode) {
        return failure(resultCode, resultCode.message());
    }

    public static <T> Result<T> failure(ResultCode resultCode, String message) {
        return new Result<>(resultCode.code(), message, null, System.currentTimeMillis());
    }
}
