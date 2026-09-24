package com.prizeflux.contract.error;

/**
 * 业务结果的稳定标识和可公开消息，由响应工厂和业务异常共同消费。
 * 这些 PFX 标识不是 HTTP 状态码；对外消息不包含密码、SQL 或异常堆栈。
 */
public enum ErrorCode {
    SUCCESS("PFX-0000", "成功"),
    INVALID_ARGUMENT("PFX-1001", "参数无效"),
    UNAUTHORIZED("PFX-1002", "未认证"),
    FORBIDDEN("PFX-1003", "无权限"),
    BUSINESS_RULE_VIOLATION("PFX-2001", "业务规则不满足"),
    RESOURCE_NOT_FOUND("PFX-2002", "资源不存在"),
    CONFLICT("PFX-2003", "资源冲突"),
    RATE_LIMITED("PFX-3001", "请求过于频繁"),
    SERVICE_UNAVAILABLE("PFX-9001", "服务暂不可用"),
    INTERNAL_ERROR("PFX-9999", "服务内部错误");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
