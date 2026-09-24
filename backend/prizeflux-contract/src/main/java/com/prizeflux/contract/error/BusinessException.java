package com.prizeflux.contract.error;

/**
 * 供应用/业务层表达已知失败的运行时异常，不依赖 Spring MVC。
 * 保存非成功 ErrorCode 和对应固定消息；未来异常处理器负责转换成响应。
 * 创建异常对象本身不等于抛出异常，调用方需要使用 throw。
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    /** null 或 SUCCESS 不表示业务失败，构造时直接拒绝。 */
    public BusinessException(ErrorCode errorCode) {
        super(validateErrorCode(errorCode).message());
        this.errorCode = errorCode;
    }

    /** 给未来异常处理器读取业务语义，不提供 HTTP 状态。 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    // Java 17 要求 super 调用位于构造器首句，因此在求父类参数时先校验。
    private static ErrorCode validateErrorCode(ErrorCode errorCode) {
        if (errorCode == null || errorCode == ErrorCode.SUCCESS) {
            throw new IllegalArgumentException("业务异常需要非成功的 ErrorCode");
        }
        return errorCode;
    }
}
