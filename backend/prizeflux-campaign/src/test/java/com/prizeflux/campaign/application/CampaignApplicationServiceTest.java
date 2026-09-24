package com.prizeflux.campaign.application;

import com.prizeflux.campaign.domain.model.Campaign;
import com.prizeflux.campaign.domain.model.CampaignStatus;
import com.prizeflux.campaign.domain.repository.CampaignRepository;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 不启动Spring或数据库，验证应用服务只负责编排领域对象和Repository端口。 */
@ExtendWith(MockitoExtension.class)
class CampaignApplicationServiceTest {

    private static final Instant STARTS_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-10-08T00:00:00Z");

    @Mock
    private CampaignRepository campaignRepository;

    private CampaignUseCase campaignUseCase;

    @BeforeEach
    void setUp() {
        campaignUseCase = new CampaignApplicationService(campaignRepository);
    }

    @Test
    void createBuildsAndSavesOneDraftCampaign() {
        Campaign created = campaignUseCase.create(1001L, "秋季抽奖", STARTS_AT, ENDS_AT);

        ArgumentCaptor<Campaign> saved = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(saved.capture());
        assertThat(created).isSameAs(saved.getValue());
        assertThat(created.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(created.id()).isEqualTo(1001L);
    }

    @Test
    void createDoesNotSaveWhenDomainConfigurationIsInvalid() {
        assertBusinessFailure(
                () -> campaignUseCase.create(1002L, " ", STARTS_AT, ENDS_AT),
                ErrorCode.INVALID_ARGUMENT);

        verifyNoInteractions(campaignRepository);
    }

    @Test
    void getReturnsExistingCampaign() {
        Campaign existing = draft(1003L);
        when(campaignRepository.findById(1003L)).thenReturn(Optional.of(existing));

        assertThat(campaignUseCase.get(1003L)).isSameAs(existing);
    }

    @Test
    void getRejectsNonPositiveIdBeforeRepositoryCall() {
        assertBusinessFailure(() -> campaignUseCase.get(0), ErrorCode.INVALID_ARGUMENT);

        verifyNoInteractions(campaignRepository);
    }

    @Test
    void getMapsMissingCampaignToResourceNotFound() {
        when(campaignRepository.findById(1004L)).thenReturn(Optional.empty());

        assertBusinessFailure(() -> campaignUseCase.get(1004L), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void publishUsesLoadedDraftAsExpectedState() {
        Campaign draft = draft(1005L);
        when(campaignRepository.findById(1005L)).thenReturn(Optional.of(draft));
        when(campaignRepository.updateStatus(
                1005L, CampaignStatus.DRAFT, CampaignStatus.PUBLISHED)).thenReturn(true);

        Campaign published = campaignUseCase.publish(1005L);

        assertThat(published.status()).isEqualTo(CampaignStatus.PUBLISHED);
        assertThat(draft.status()).isEqualTo(CampaignStatus.DRAFT);
        verify(campaignRepository).updateStatus(
                1005L, CampaignStatus.DRAFT, CampaignStatus.PUBLISHED);
    }

    @Test
    void publishMapsConditionalUpdateMissToConflict() {
        Campaign draft = draft(1006L);
        when(campaignRepository.findById(1006L)).thenReturn(Optional.of(draft));
        when(campaignRepository.updateStatus(
                1006L, CampaignStatus.DRAFT, CampaignStatus.PUBLISHED)).thenReturn(false);

        assertBusinessFailure(() -> campaignUseCase.publish(1006L), ErrorCode.CONFLICT);
    }

    @Test
    void publishDoesNotUpdateWhenDomainRejectsCurrentState() {
        Campaign published = draft(1007L).publish();
        when(campaignRepository.findById(1007L)).thenReturn(Optional.of(published));

        assertBusinessFailure(
                () -> campaignUseCase.publish(1007L),
                ErrorCode.BUSINESS_RULE_VIOLATION);
        verify(campaignRepository, never()).updateStatus(anyLong(), any(), any());
    }

    @Test
    void closeUsesDraftAsExpectedState() {
        Campaign draft = draft(1008L);
        when(campaignRepository.findById(1008L)).thenReturn(Optional.of(draft));
        when(campaignRepository.updateStatus(
                1008L, CampaignStatus.DRAFT, CampaignStatus.CLOSED)).thenReturn(true);

        Campaign closed = campaignUseCase.close(1008L);

        assertThat(closed.status()).isEqualTo(CampaignStatus.CLOSED);
        verify(campaignRepository).updateStatus(1008L, CampaignStatus.DRAFT, CampaignStatus.CLOSED);
    }

    @Test
    void closeUsesPublishedAsExpectedState() {
        Campaign published = draft(1009L).publish();
        when(campaignRepository.findById(1009L)).thenReturn(Optional.of(published));
        when(campaignRepository.updateStatus(
                1009L, CampaignStatus.PUBLISHED, CampaignStatus.CLOSED)).thenReturn(true);

        Campaign closed = campaignUseCase.close(1009L);

        assertThat(closed.status()).isEqualTo(CampaignStatus.CLOSED);
        verify(campaignRepository).updateStatus(
                1009L, CampaignStatus.PUBLISHED, CampaignStatus.CLOSED);
    }

    @Test
    void closeMapsConditionalUpdateMissToConflict() {
        Campaign published = draft(1010L).publish();
        when(campaignRepository.findById(1010L)).thenReturn(Optional.of(published));
        when(campaignRepository.updateStatus(
                1010L, CampaignStatus.PUBLISHED, CampaignStatus.CLOSED)).thenReturn(false);

        assertBusinessFailure(() -> campaignUseCase.close(1010L), ErrorCode.CONFLICT);
    }

    @Test
    void closeDoesNotUpdateWhenCampaignIsAlreadyClosed() {
        Campaign closed = draft(1011L).close();
        when(campaignRepository.findById(1011L)).thenReturn(Optional.of(closed));

        assertBusinessFailure(
                () -> campaignUseCase.close(1011L),
                ErrorCode.BUSINESS_RULE_VIOLATION);
        verify(campaignRepository, never()).updateStatus(anyLong(), any(), any());
    }

    private static Campaign draft(long id) {
        return new Campaign(id, "活动-" + id, STARTS_AT, ENDS_AT);
    }

    private static void assertBusinessFailure(Runnable action, ErrorCode expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
    }
}
