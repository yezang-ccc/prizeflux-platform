package com.prizeflux.campaign.controller;

import com.prizeflux.campaign.PrizeFluxCampaignApplication;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HealthController 的 HTTP 契约测试，属于测试代码，不会被打进生产运行包。
 *
 * <p>本测试不直接调用 {@code health()} 方法，而是模拟一次 HTTP GET 请求。
 * 因此可以同时验证：Spring Boot 能启动、组件能被扫描、路径能匹配、Controller 能被调用、
 * 返回对象能被序列化为 JSON。</p>
 */
@SpringBootTest(classes = PrizeFluxCampaignApplication.class)
@AutoConfigureMockMvc
class HealthControllerTest {

    /**
     * Spring 提供的模拟 HTTP 客户端。
     * 它不需要真的监听 8081 端口，却会经过 Spring MVC 的请求处理流程，
     * 因此比直接调用 Controller 方法更接近真实接口验证。
     */
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CampaignRepository campaignRepository;

    /**
     * 验证健康接口对调用方承诺的 HTTP 契约。
     *
     * MockMvc 执行请求可能抛出受检异常；这里由 JUnit 测试框架统一处理。
     */
    @Test
    void healthEndpointReturnsSafeAndStableJson() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // STRICT：除约定字段外不允许多出系统信息、配置或堆栈等内容。
                .andExpect(content().json("""
                        {"status":"UP","application":"prizeflux-campaign"}
                        """, JsonCompareMode.STRICT));
    }
}
