package com.prizeflux.ai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prizeflux.ai.harness.campaign.CampaignReadClient;
import com.prizeflux.ai.harness.campaign.CampaignSummary;
import com.prizeflux.ai.harness.safety.Actor;
import com.prizeflux.ai.harness.safety.Role;
import com.prizeflux.ai.infrastructure.campaign.CampaignIdentityClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "prizeflux.campaign.base-url=http://localhost:18080")
@AutoConfigureMockMvc
class AiSessionControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CampaignIdentityClient identities;

    @MockitoBean
    private CampaignReadClient campaigns;

    @Test
    void operatorCanCreateContinueReadAndDeleteSession() throws Exception {
        operator("operator-token", 7);
        when(campaigns.read(eq(1005L), eq("operator-token"), any(Duration.class)))
                .thenReturn(new CampaignSummary(1005, "Autumn", "PUBLISHED"));
        String id = createSession();

        mvc.perform(post("/api/ai/sessions/{id}/messages", id)
                        .header("Authorization", "Bearer operator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"summarize this\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.result.simulated").value(true))
                .andExpect(jsonPath("$.data.result.answer").value(org.hamcrest.Matchers.containsString("Autumn")));

        mvc.perform(post("/api/ai/sessions/{id}/messages", id)
                        .header("Authorization", "Bearer operator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"and the status?\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.omittedMessageCount").value(0));

        mvc.perform(get("/api/ai/sessions/{id}", id).header("Authorization", "Bearer operator-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.events.length()").value(8))
                .andExpect(jsonPath("$.data.events[0].content").value("summarize this"))
                .andExpect(jsonPath("$.data.events[4].content").value("and the status?"));

        mvc.perform(delete("/api/ai/sessions/{id}", id).header("Authorization", "Bearer operator-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ai/sessions/{id}", id).header("Authorization", "Bearer operator-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PFX-2002"));

        verify(identities, org.mockito.Mockito.times(6)).verify("operator-token");
        verify(campaigns, org.mockito.Mockito.times(2)).read(eq(1005L), eq("operator-token"), any(Duration.class));
    }

    @Test
    void anotherOperatorCannotReadSessionAndGetsSameNotFoundAsMissing() throws Exception {
        operator("owner-token", 7);
        operator("other-token", 8);
        String id = createSession("owner-token");

        mvc.perform(get("/api/ai/sessions/{id}", id).header("Authorization", "Bearer other-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PFX-2002"));
        mvc.perform(get("/api/ai/sessions/{id}", "1b5d51ed-80a3-4e41-a2f4-c00ae19f65f0")
                        .header("Authorization", "Bearer other-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PFX-2002"));
    }

    @Test
    void everySessionRequestRequiresFreshVerifiedOperatorAndVersionConflictsDoNotCallTool() throws Exception {
        operator("operator-token", 7);
        when(identities.verify("user-token")).thenReturn(new Actor(9, Role.USER));
        String id = createSession();

        mvc.perform(post("/api/ai/sessions/{id}/messages", id)
                        .header("Authorization", "Bearer operator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"stale\",\"expectedVersion\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PFX-2003"));
        verify(campaigns, never()).read(eq(1005L), any(), any(Duration.class));

        mvc.perform(get("/api/ai/sessions/{id}", id).header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PFX-1003"));
        mvc.perform(get("/api/ai/sessions/{id}", id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PFX-1002"));
        verify(identities, org.mockito.Mockito.times(2)).verify("operator-token");
    }

    @Test
    void concurrentMessagesWithSameVersionOnlyAppendOneHttpTurn() throws Exception {
        operator("operator-token", 7);
        CountDownLatch bothReadsStarted = new CountDownLatch(2);
        CountDownLatch releaseReads = new CountDownLatch(1);
        when(campaigns.read(eq(1005L), eq("operator-token"), any(Duration.class))).thenAnswer(invocation -> {
            bothReadsStarted.countDown();
            if (!releaseReads.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent read barrier timed out");
            }
            return new CampaignSummary(1005, "Autumn", "PUBLISHED");
        });
        String id = createSession();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> postMessageStatus(id, "first request", 0));
            Future<Integer> second = pool.submit(() -> postMessageStatus(id, "second request", 0));

            assertTrue(bothReadsStarted.await(5, TimeUnit.SECONDS),
                    "both requests should pass the initial version check before either append");
            releaseReads.countDown();
            List<Integer> statuses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .stream().sorted().toList();
            assertEquals(List.of(200, 409), statuses);
        } finally {
            releaseReads.countDown();
            pool.shutdownNow();
        }

        mvc.perform(get("/api/ai/sessions/{id}", id).header("Authorization", "Bearer operator-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.events.length()").value(4));
        verify(campaigns, org.mockito.Mockito.times(2))
                .read(eq(1005L), eq("operator-token"), any(Duration.class));
    }

    @Test
    void malformedSessionIdAndMessageBudgetHaveStableClientErrors() throws Exception {
        operator("operator-token", 7);
        mvc.perform(get("/api/ai/sessions/not-a-uuid").header("Authorization", "Bearer operator-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PFX-1001"));
        String id = createSession();
        mvc.perform(post("/api/ai/sessions/{id}/messages", id)
                        .header("Authorization", "Bearer operator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"" + "x".repeat(1001)
                                + "\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PFX-1001"));
    }

    private String createSession() throws Exception {
        return createSession("operator-token");
    }

    private String createSession(String token) throws Exception {
        var result = mvc.perform(post("/api/ai/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).path("data").path("sessionId").asText();
    }

    private int postMessageStatus(String id, String question, long expectedVersion) throws Exception {
        return mvc.perform(post("/api/ai/sessions/{id}/messages", id)
                        .header("Authorization", "Bearer operator-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":1005,\"question\":\"" + question
                                + "\",\"expectedVersion\":" + expectedVersion + "}"))
                .andReturn().getResponse().getStatus();
    }

    private void operator(String token, long userId) {
        when(identities.verify(token)).thenReturn(new Actor(userId, Role.OPERATOR));
    }
}
