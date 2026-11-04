package com.example.erupt.common;

/**
 * 集中定义的错误码，避免错误码散落各处。
 */
public enum ResultCode {

    /** 操作成功 */
    SUCCESS(200, "操作成功"),
    /** 请求参数错误 */
    BAD_REQUEST(400, "请求参数错误"),
    /** 未认证：未登录、Token 无效或已过期，前端应据此跳转登录页 */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 已认证但无权限：例如账号被禁用 */
    FORBIDDEN(403, "无权限访问"),
    /** 请求方法不被接口支持：例如对只接收 POST 的上传接口发 GET */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    /** 账号/资源已存在 */
    CONFLICT(409, "资源已存在"),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),
    /** 请求过于频繁：触发登录失败次数限制时会用到 */
    TOO_MANY_REQUESTS(429, "操作过于频繁，请稍后再试"),
    /** 服务内部异常 */
    INTERNAL_ERROR(500, "服务内部异常");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
