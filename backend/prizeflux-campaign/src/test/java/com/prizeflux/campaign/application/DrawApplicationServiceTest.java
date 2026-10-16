package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.random.RandomSource;
import com.prizeflux.campaign.domain.repository.PrizeInventoryRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DrawApplicationServiceTest {
    private final DrawRepository repository = mock(DrawRepository.class);
    private final PrizeInventoryRepository inventory = mock(PrizeInventoryRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DrawApplicationService service = new DrawApplicationService(
            repository, inventory,
            () -> new AuthenticatedActor(7L, "user", AppUser.Role.USER),
            mock(RandomSource.class),
            Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC),
            transactions, mock(DrawTelemetry.class));

    @Test
    void replaysPeerCommitFoundAfterCampaignClosed() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.lockPublishedConfig(42L)).thenReturn(Optional.of(closedConfig()));
        DrawResult committed = new DrawResult(91L, 42L, 7L, DrawOutcome.NO_PRIZE, null, null);
        byte[] fingerprint = fingerprintFor(42L);
        when(repository.findByKey(7L, "request-key-1", "DRAW"))
                .thenReturn(Optional.empty(), Optional.of(
                        new DrawRepository.StoredDraw(fingerprint, true, committed)));

        assertThat(service.draw(new DrawCommand(42L, "request-key-1"))).isEqualTo(committed);
        verifyNoInteractions(inventory);
    }

    @Test
    void newKeyRemainsRejectedAfterCampaignClosed() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.lockPublishedConfig(42L)).thenReturn(Optional.of(closedConfig()));
        when(repository.findByKey(7L, "fresh-key", "DRAW")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.draw(new DrawCommand(42L, "fresh-key")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verifyNoInteractions(inventory);
    }

    @Test
    void committedKeyWithDifferentCampaignStillConflicts() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.lockPublishedConfig(42L)).thenReturn(Optional.of(closedConfig()));
        DrawResult committed = new DrawResult(91L, 43L, 7L, DrawOutcome.NO_PRIZE, null, null);
        when(repository.findByKey(7L, "request-key-2", "DRAW"))
                .thenReturn(Optional.empty(), Optional.of(
                        new DrawRepository.StoredDraw(fingerprintFor(43L), true, committed)));

        assertThatThrownBy(() -> service.draw(new DrawCommand(42L, "request-key-2")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    private static DrawRepository.ConfigSnapshot closedConfig() {
        return new DrawRepository.ConfigSnapshot(1, 1, "CLOSED",
                Instant.parse("2026-10-16T00:00:00Z"),
                Instant.parse("2026-10-17T00:00:00Z"), List.of());
    }

    private static byte[] fingerprintFor(long campaignId) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(("DRAW|" + campaignId)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
