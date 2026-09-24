package com.prizeflux.testsupport.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.controller.advice.GlobalExceptionHandler;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.api.ApiResponse;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D2-02 的 MVC 链路测试：请求经过 JSON 转换、字段校验、异常处理及响应序列化。
 * 本包在应用扫描根之外，夹具只显式注册到此测试上下文，不提供生产活动 API。
 */
@SpringBootTest(classes = PrizeFluxCampaignApplication.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({
        GlobalExceptionHandlerTest.FixtureController.class,
        GlobalExceptionHandlerTest.RepositoryStubConfiguration.class
})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    private static final String ROOT = "/__test/d2-02";
    private static final String FAKE_SECRET = "FAKE_SECRET_d2_02";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FixtureController fixture;

    @BeforeEach
    void resetBusinessCalls() {
        fixture.calls.set(0);
    }

    @Test
    void validRequestIsConvertedAndReturnsSuccess() throws Exception {
        assertValidName(" 测试活动 "); // 不暗中 trim 请求数据。
    }

    @Test
    void nameOfExactly100CharactersIsValid() throws Exception {
        assertValidName("奖".repeat(100));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void invalidNameStopsBeforeBusinessMethod(String name) throws Exception {
        assertFailure(postBody(nameJson(name)), 400, "PFX-1001", "参数无效");
        assertThat(fixture.calls.get()).isZero();
    }

    @Test
    void nameOf101CharactersIsRejectedWithoutLoggingItsValue(CapturedOutput output) throws Exception {
        String name = FAKE_SECRET + "x".repeat(101 - FAKE_SECRET.length());
        assertFailure(postBody(nameJson(name)), 400, "PFX-1001", "参数无效");
        assertThat(fixture.calls.get()).isZero();
        assertThat(output.getAll()).contains("request_validation_failed").doesNotContain(FAKE_SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"FAKE_SECRET_d2_02\"",
            "{\"name\":{\"secret\":\"FAKE_SECRET_d2_02\"}}",
            "null"
    })
    void unreadableBodyStopsBeforeBusinessAndDoesNotLeak(String body, CapturedOutput output) throws Exception {
        assertFailure(postBody(body), 400, "PFX-1001", "参数无效");
        assertThat(fixture.calls.get()).isZero();
        assertThat(output.getAll()).contains("request_body_unreadable").doesNotContain(FAKE_SECRET);
    }

    @Test
    void missingBodyIsA400Response() throws Exception {
        assertFailure(mockMvc.perform(post(ROOT + "/requests").contentType(MediaType.APPLICATION_JSON)),
                400, "PFX-1001", "参数无效");
        assertThat(fixture.calls.get()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = "SUCCESS", mode = EnumSource.Mode.EXCLUDE)
    void businessFailureMapsExplicitStatusAndSafeJson(ErrorCode code, CapturedOutput output) throws Exception {
        int expectedStatus = switch (code) {
            case INVALID_ARGUMENT -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case RESOURCE_NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case BUSINESS_RULE_VIOLATION -> 422;
            case RATE_LIMITED -> 429;
            case SERVICE_UNAVAILABLE -> 503;
            case INTERNAL_ERROR -> 500;
            default -> throw new AssertionError("测试输入不应为 SUCCESS");
        };
        String expectedCode = switch (code) {
            case INVALID_ARGUMENT -> "PFX-1001";
            case UNAUTHORIZED -> "PFX-1002";
            case FORBIDDEN -> "PFX-1003";
            case BUSINESS_RULE_VIOLATION -> "PFX-2001";
            case RESOURCE_NOT_FOUND -> "PFX-2002";
            case CONFLICT -> "PFX-2003";
            case RATE_LIMITED -> "PFX-3001";
            case SERVICE_UNAVAILABLE -> "PFX-9001";
            case INTERNAL_ERROR -> "PFX-9999";
            default -> throw new AssertionError("测试输入不应为 SUCCESS");
        };
        String expectedMessage = switch (code) {
            case INVALID_ARGUMENT -> "参数无效";
            case UNAUTHORIZED -> "未认证";
            case FORBIDDEN -> "无权限";
            case BUSINESS_RULE_VIOLATION -> "业务规则不满足";
            case RESOURCE_NOT_FOUND -> "资源不存在";
            case CONFLICT -> "资源冲突";
            case RATE_LIMITED -> "请求过于频繁";
            case SERVICE_UNAVAILABLE -> "服务暂不可用";
            case INTERNAL_ERROR -> "服务内部错误";
            default -> throw new AssertionError("测试输入不应为 SUCCESS");
        };
        assertFailure(mockMvc.perform(get(ROOT + "/business/" + code.name())),
                expectedStatus, expectedCode, expectedMessage);
        assertThat(output.getAll()).contains("business_failure", expectedCode);
    }

    @Test
    void unknownExceptionReturnsSafe500ButKeepsServerDiagnostics(CapturedOutput output) throws Exception {
        assertFailure(mockMvc.perform(get(ROOT + "/unknown")), 500, "PFX-9999", "服务内部错误");
        assertThat(output.getAll()).contains("unexpected_exception", "IllegalStateException", FAKE_SECRET);
    }

    @Test
    void invalidFactoryCallIsAProgrammingFailureNotClient400() throws Exception {
        assertFailure(mockMvc.perform(get(ROOT + "/invalid-factory")), 500, "PFX-9999", "服务内部错误");
    }

    @Test
    void nonNumericPathVariableReturnsSafeInvalidArgument() throws Exception {
        assertFailure(mockMvc.perform(get(ROOT + "/numbers/not-a-number")),
                400, "PFX-1001", "参数无效");
    }

    @Test
    void unsupportedHttpMethodKeeps405AndAllowHeader() throws Exception {
        mockMvc.perform(get(ROOT + "/requests"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
    }

    @Test
    void unsupportedContentTypeKeeps415() throws Exception {
        mockMvc.perform(post(ROOT + "/requests").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(fixture.calls.get()).isZero();
    }

    @Test
    void productionConfigurationDoesNotScanTestFixture() {
        // 独立模拟 Servlet 上下文，只有应用配置；不启动嵌入式服务器或监听端口。
        new WebApplicationContextRunner()
                // ContextRunner不读取测试application.yml，这里显式保持与快速测试相同的数据库隔离。
                .withPropertyValues("spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
                        "prizeflux.persistence.campaign.enabled=false")
                .withBean(CampaignRepository.class, () -> mock(CampaignRepository.class))
                .withUserConfiguration(PrizeFluxCampaignApplication.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class)
                            .doesNotHaveBean(FixtureController.class);
                    MockMvc productionMvc = MockMvcBuilders.webAppContextSetup(context).build();
                    productionMvc.perform(get(ROOT + "/unknown")).andExpect(status().isNotFound());
                    productionMvc.perform(get("/api/health")).andExpect(status().isOk())
                            .andExpect(content().json("{\"status\":\"UP\",\"application\":\"prizeflux-campaign\"}",
                                    JsonCompareMode.STRICT));
                });
    }

    private void assertValidName(String name) throws Exception {
        String expected = objectMapper.writeValueAsString(Map.of(
                "code", "PFX-0000", "message", "成功", "data", Map.of("name", name)));
        postBody(nameJson(name)).andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expected, JsonCompareMode.STRICT));
        assertThat(fixture.calls.get()).isEqualTo(1);
    }

    private String nameJson(String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name); // Map.of 不允许 null，测试必须能够构造 null 字段输入。
        return objectMapper.writeValueAsString(body);
    }

    private ResultActions postBody(String body) throws Exception {
        return mockMvc.perform(post(ROOT + "/requests").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void assertFailure(ResultActions result, int httpStatus, String code, String message) throws Exception {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("code", code);
        expected.put("message", message);
        expected.put("data", null);
        result.andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(expected), JsonCompareMode.STRICT));
    }

    /** 仅测试用：回显不代表创建成功，不访问数据库。 */
    @RestController
    @RequestMapping(ROOT)
    static class FixtureController {
        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping(value = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE)
        ApiResponse<FixtureRequest> echo(@Valid @RequestBody FixtureRequest request) {
            calls.incrementAndGet();
            return ApiResponse.success(request);
        }

        @GetMapping("/business/{code}")
        ApiResponse<Void> business(@PathVariable ErrorCode code) {
            throw new BusinessException(code);
        }

        @GetMapping("/unknown")
        ApiResponse<Void> unknown() {
            throw new IllegalStateException("测试诊断：" + FAKE_SECRET);
        }

        @GetMapping("/numbers/{id}")
        ApiResponse<Long> number(@PathVariable long id) {
            return ApiResponse.success(id);
        }

        @GetMapping("/invalid-factory")
        ApiResponse<Void> invalidFactory() {
            return ApiResponse.failure(ErrorCode.SUCCESS);
        }
    }

    /** 只供D2-02异常边界使用，不复制或替代生产活动创建请求。 */
    private record FixtureRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 100)
            String name) {
    }

    /** 数据库被测试配置排除时，只为应用服务提供无行为的端口依赖。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class RepositoryStubConfiguration {

        @Bean
        CampaignRepository campaignRepository() {
            return mock(CampaignRepository.class);
        }
    }
}
