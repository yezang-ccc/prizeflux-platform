package com.prizeflux.contract.error;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定跨模块错误码的公开标识、消息和唯一性。 */
class ErrorCodeTest {

    @Test
    void errorCodesHaveExactStableIdentifiersAndSafeMessages() {
        Map<ErrorCode, String> codes = Map.of(
                ErrorCode.SUCCESS, "PFX-0000",
                ErrorCode.INVALID_ARGUMENT, "PFX-1001",
                ErrorCode.UNAUTHORIZED, "PFX-1002",
                ErrorCode.FORBIDDEN, "PFX-1003",
                ErrorCode.BUSINESS_RULE_VIOLATION, "PFX-2001",
                ErrorCode.RESOURCE_NOT_FOUND, "PFX-2002",
                ErrorCode.CONFLICT, "PFX-2003",
                ErrorCode.RATE_LIMITED, "PFX-3001",
                ErrorCode.SERVICE_UNAVAILABLE, "PFX-9001",
                ErrorCode.INTERNAL_ERROR, "PFX-9999"
        );
        Map<ErrorCode, String> messages = Map.of(
                ErrorCode.SUCCESS, "成功",
                ErrorCode.INVALID_ARGUMENT, "参数无效",
                ErrorCode.UNAUTHORIZED, "未认证",
                ErrorCode.FORBIDDEN, "无权限",
                ErrorCode.BUSINESS_RULE_VIOLATION, "业务规则不满足",
                ErrorCode.RESOURCE_NOT_FOUND, "资源不存在",
                ErrorCode.CONFLICT, "资源冲突",
                ErrorCode.RATE_LIMITED, "请求过于频繁",
                ErrorCode.SERVICE_UNAVAILABLE, "服务暂不可用",
                ErrorCode.INTERNAL_ERROR, "服务内部错误"
        );
        Set<String> seenCodes = new HashSet<>();

        assertEquals(codes.size(), ErrorCode.values().length);
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertAll(
                    () -> assertEquals(codes.get(errorCode), errorCode.code()),
                    () -> assertEquals(messages.get(errorCode), errorCode.message()),
                    () -> assertFalse(errorCode.code().isBlank()),
                    () -> assertFalse(errorCode.message().isBlank()),
                    () -> assertTrue(seenCodes.add(errorCode.code()), "业务错误标识不能重复")
            );
        }
    }
}
