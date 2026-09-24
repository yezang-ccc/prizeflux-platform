package com.prizeflux.contract.api;

import com.prizeflux.contract.error.ErrorCode;

/**
 * 供未来 Controller 和异常处理器使用的统一 Java 响应契约。
 * code 表达业务结果，message 表达可公开消息，T 表达成功数据的类型。
 * record 固定三个字段的引用，不会深复制或冻结 data；可变数据仍需调用方管理。
 * 当前没有接入 HTTP，也不负责选择 HTTP 状态码。
 */
public record ApiResponse<T>(String code, String message, T data) {
    /** 所有构造路径都会经过这里；非法输入抛 IllegalArgumentException。 */
    public ApiResponse {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空或空白");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空或空白");
        }
        if (!ErrorCode.SUCCESS.code().equals(code) && data != null) {
            throw new IllegalArgumentException("失败响应不能携带 data");
        }
    }

    /** 保留传入的 T 数据（允许 null），返回固定成功标识和消息。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data);
    }

    /**
     * 将非成功枚举转换成无数据的失败响应，拒绝 null 和 SUCCESS。
     * 只使用枚举的固定消息，不接收内部异常详情作为对外消息。
     */
    public static ApiResponse<Void> failure(ErrorCode errorCode) {
        if (errorCode == null || errorCode == ErrorCode.SUCCESS) {
            throw new IllegalArgumentException("失败响应需要非成功的 ErrorCode");
        }
        return new ApiResponse<>(errorCode.code(), errorCode.message(), null);
    }
}
