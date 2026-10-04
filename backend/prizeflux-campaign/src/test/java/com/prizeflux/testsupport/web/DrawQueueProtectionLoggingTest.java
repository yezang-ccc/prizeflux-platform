package com.prizeflux.testsupport.web;

import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException;
import com.prizeflux.campaign.controller.advice.GlobalExceptionHandler;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DrawQueueProtectionLoggingTest {
    @Test
    void keepsGeneric503ContractAndLogsOnlyTheStableProtectionReason(CapturedOutput output) {
        DrawQueueProtectionUnavailableException failure = new DrawQueueProtectionUnavailableException(
                DrawQueueProtectionUnavailableException.Reason.MYSQL_BACKLOG_LIMIT);

        var response = new GlobalExceptionHandler().handleBusinessException(failure);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.code());
        assertThat(output.getAll()).contains("draw_queue_protection_rejected", "reason=MYSQL_BACKLOG_LIMIT")
                .doesNotContain("userId", "requestKey", "campaignId");
    }
}
