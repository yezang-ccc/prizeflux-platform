package com.prizeflux.ai.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prizeflux.ai.harness.campaign.CampaignSummary;
import com.prizeflux.ai.harness.campaign.CampaignReadClient;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.Role;
import com.prizeflux.ai.infrastructure.campaign.CampaignIdentityClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "prizeflux.campaign.base-url=http://localhost:18080")
@AutoConfigureMockMvc
class AiCampaignControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CampaignIdentityClient identities;

    @MockitoBean
    private CampaignReadClient campaigns;

    @Test
    void healthIsPublicAndReportsUp() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void missingBearerIs401AndDoesNotCallCampaign() throws Exception {
        mvc.perform(post("/api/ai/campaign-summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"status?\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PFX-1002"));
        verify(identities, never()).verify(any());
        verify(campaigns, never()).read(anyLong(), any(), any());
    }

    @Test
    void callerCannotPromoteSelfWithBodyFields() throws Exception {
        when(identities.verify("token-user")).thenReturn(new Actor(22, Role.USER));
        mvc.perform(post("/api/ai/campaign-summary")
                        .header("Authorization", "Bearer token-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"status?\","
                                + "\"userId\":1,\"role\":\"OPERATOR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PFX-1003"));
        verify(campaigns, never()).read(anyLong(), any(), any());
    }

    @Test
    void operatorCanReadUsingSameVerifiedBearerAndGetsSimulatedTrace() throws Exception {
        when(identities.verify("operator-token")).thenReturn(new Actor(7, Role.OPERATOR));
        when(campaigns.read(eq(1005L), eq("operator-token"), any(Duration.class)))
                .thenReturn(new CampaignSummary(1005, "Autumn", "PUBLISHED"));
        mvc.perform(post("/api/ai/campaign-summary")
                        .header("Authorization", "Bearer operator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"summarize\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PFX-0000"))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.trace[1].toolName").value("campaign.summary"));
        verify(campaigns).read(eq(1005L), eq("operator-token"), any(Duration.class));
    }

    @Test
    void malformedRequestReturnsStableBadRequest() throws Exception {
        mvc.perform(post("/api/ai/campaign-summary")
                        .header("Authorization", "Bearer operator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":0,\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PFX-1001"));
        verify(identities, never()).verify(any());
    }
}
