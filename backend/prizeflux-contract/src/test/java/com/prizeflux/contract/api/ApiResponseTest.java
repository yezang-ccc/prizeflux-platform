package com.prizeflux.contract.api;

import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 Java 契约测试：JUnit 直接调用构造器和工厂，不启动 Spring 或 HTTP 服务器。
 * 参数化用例用同一条规则检查多个输入；每个输入会分别计入测试报告。
 */
class ApiResponseTest {

    @Test
    void successCarriesStringDataAndStableSuccessMeaning() {
        ApiResponse<String> response = ApiResponse.success("活动创建成功");

        assertAll(
                () -> assertEquals("PFX-0000", response.code()),
                () -> assertEquals("成功", response.message()),
                () -> assertEquals("活动创建成功", response.data())
        );
    }

    @Test
    void successCarriesTypedRecordDataWithoutReplacingIt() {
        CampaignData data = new CampaignData(7L, "测试活动");
        ApiResponse<CampaignData> response = ApiResponse.success(data);

        assertAll(
                () -> assertEquals("PFX-0000", response.code()),
                () -> assertEquals("成功", response.message()),
                () -> assertSame(data, response.data()),
                () -> assertEquals(7L, response.data().id())
        );
    }

    @Test
    void successAllowsAnEmptyPayload() {
        ApiResponse<Void> response = ApiResponse.success(null);

        assertAll(
                () -> assertEquals("PFX-0000", response.code()),
                () -> assertEquals("成功", response.message()),
                () -> assertNull(response.data())
        );
    }

    @Test
    void successDoesNotPromiseADeepCopyOfMutablePayloads() {
        int[] data = {10, 20};
        ApiResponse<int[]> response = ApiResponse.success(data);

        assertSame(data, response.data());
        data[0] = 99;
        assertEquals(99, response.data()[0]);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = "SUCCESS", mode = EnumSource.Mode.EXCLUDE)
    void failureCarriesSafeErrorMeaningWithoutData(ErrorCode errorCode) {
        ApiResponse<Void> response = ApiResponse.failure(errorCode);

        assertAll(
                () -> assertEquals(errorCode.code(), response.code()),
                () -> assertEquals(errorCode.message(), response.message()),
                () -> assertNull(response.data())
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void constructorRejectsMissingCode(String code) {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiResponse<>(code, "成功", null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void constructorRejectsMissingMessage(String message) {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiResponse<>("PFX-0000", message, null));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = "SUCCESS", mode = EnumSource.Mode.EXCLUDE)
    void constructorRejectsPayloadsOnFailures(ErrorCode errorCode) {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiResponse<>(errorCode.code(), errorCode.message(), "不应携带的数据"));
    }

    @Test
    void failureRejectsNullErrorCode() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponse.failure(null));
    }

    @Test
    void failureRejectsTheSuccessCode() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponse.failure(ErrorCode.SUCCESS));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = "SUCCESS", mode = EnumSource.Mode.EXCLUDE)
    void businessExceptionRetainsItsErrorCodeAndSafeMessage(ErrorCode errorCode) {
        BusinessException exception = new BusinessException(errorCode);

        assertAll(
                () -> assertSame(errorCode, exception.getErrorCode()),
                () -> assertEquals(errorCode.message(), exception.getMessage())
        );
    }

    @Test
    void businessExceptionRejectsNullErrorCode() {
        assertThrows(IllegalArgumentException.class, () -> new BusinessException(null));
    }

    @Test
    void businessExceptionRejectsTheSuccessCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new BusinessException(ErrorCode.SUCCESS));
    }

    // 仅供测试泛型用途，不是已经实现的活动业务接口。
    private record CampaignData(long id, String name) {}
}
