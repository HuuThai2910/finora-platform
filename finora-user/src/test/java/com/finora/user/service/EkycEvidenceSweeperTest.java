package com.finora.user.service;

import com.finora.user.config.EkycStorageProperties;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycAttemptStatus;
import com.finora.user.domain.EkycDocumentDecision;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.support.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra worker quét dọn: bỏ lượt quá hạn, xoá object theo hạn lưu, giữ object xoá lỗi để thử lại. */
@ExtendWith(MockitoExtension.class)
class EkycEvidenceSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

    @Mock private EkycAttemptRepository attemptRepository;
    @Mock private EkycEvidenceRepository evidenceRepository;
    @Mock private EkycEvidenceService evidenceService;

    private EkycEvidenceSweeper sweeper;

    @BeforeEach
    void setUp() {
        EkycStorageProperties properties = new EkycStorageProperties();
        properties.setRetentionDays(30);
        sweeper = new EkycEvidenceSweeper(attemptRepository, evidenceRepository, evidenceService, properties,
                EkycTestData.transactionTemplate(), Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(attemptRepository.findByStatusCreatedBefore(eq(EkycAttemptStatus.IN_PROGRESS), any(), any()))
                .thenReturn(List.of());
        lenient().when(attemptRepository.findDecidedWithDraftBefore(anyCollection(), any(), any())).thenReturn(List.of());
        lenient().when(evidenceRepository.findLiveOfAttemptsInStatus(any(), anyString(), any())).thenReturn(List.of());
        lenient().when(evidenceRepository.findLiveByStageCreatedBefore(any(), any(), anyString(), any())).thenReturn(List.of());
        lenient().when(evidenceRepository.findLiveCreatedBeforeWithdrawal(anyString(), any())).thenReturn(List.of());
        lenient().when(evidenceService.envPrefix()).thenReturn("ekyc/dev/");
    }

    @Test
    void abandonsAttemptsOpenForMoreThanADay() {
        EkycAttempt stale = EkycTestData.attempt(1L, UUID.randomUUID(), 7L, EkycDocumentDecision.AUTO_OK,
                NOW.minus(Duration.ofHours(30)));
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        when(attemptRepository.findByStatusCreatedBefore(eq(EkycAttemptStatus.IN_PROGRESS), cutoff.capture(), any()))
                .thenReturn(List.of(stale));
        when(evidenceService.isEnabled()).thenReturn(false);

        EkycEvidenceSweeper.SweepReport report = sweeper.sweep();

        assertThat(report.abandonedAttempts()).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(EkycAttemptStatus.ABANDONED);
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void deletesExpiredFinalEvidenceWithinOwnEnvironmentOnly() {
        when(evidenceService.isEnabled()).thenReturn(true);
        EkycEvidence expired = EkycTestData.evidence(5L, 1L, EkycEvidenceKind.LIVENESS_VIDEO, "ekyc/dev/7/a/liveness-1.mp4",
                EkycEvidenceStage.FINAL, NOW.minus(Duration.ofDays(31)));
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        when(evidenceRepository.findLiveByStageCreatedBefore(eq(EkycEvidenceStage.FINAL), cutoff.capture(),
                eq("ekyc/dev/%"), any())).thenReturn(List.of(expired));
        when(evidenceService.deleteObjects(List.of("ekyc/dev/7/a/liveness-1.mp4")))
                .thenReturn(List.of("ekyc/dev/7/a/liveness-1.mp4"));

        EkycEvidenceSweeper.SweepReport report = sweeper.sweep();

        assertThat(report.deletedObjects()).isEqualTo(1);
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        verify(evidenceRepository).markDeleted(List.of(5L), NOW);
    }

    @Test
    void leftoversOfAbandonedAttemptsAndWithdrawalsAreDeleted() {
        when(evidenceService.isEnabled()).thenReturn(true);
        EkycEvidence abandoned = EkycTestData.evidence(6L, 2L, EkycEvidenceKind.CCCD_FRONT, "ekyc/dev/7/b/cccd-front.jpg",
                EkycEvidenceStage.PENDING, NOW);
        EkycEvidence withdrawn = EkycTestData.evidence(7L, 3L, EkycEvidenceKind.CCCD_BACK, "ekyc/dev/8/c/cccd-back.jpg",
                EkycEvidenceStage.FINAL, NOW);
        when(evidenceRepository.findLiveOfAttemptsInStatus(eq(EkycAttemptStatus.ABANDONED), eq("ekyc/dev/%"), any()))
                .thenReturn(List.of(abandoned));
        when(evidenceRepository.findLiveCreatedBeforeWithdrawal(eq("ekyc/dev/%"), any())).thenReturn(List.of(withdrawn));
        when(evidenceService.deleteObjects(List.of("ekyc/dev/7/b/cccd-front.jpg"))).thenReturn(List.of("ekyc/dev/7/b/cccd-front.jpg"));
        when(evidenceService.deleteObjects(List.of("ekyc/dev/8/c/cccd-back.jpg"))).thenReturn(List.of("ekyc/dev/8/c/cccd-back.jpg"));

        assertThat(sweeper.sweep().deletedObjects()).isEqualTo(2);
        verify(evidenceRepository).markDeleted(List.of(6L), NOW);
        verify(evidenceRepository).markDeleted(List.of(7L), NOW);
    }

    @Test
    void failedDeletionKeepsRowForNextRun() {
        when(evidenceService.isEnabled()).thenReturn(true);
        EkycEvidence expired = EkycTestData.evidence(5L, 1L, EkycEvidenceKind.CCCD_FRONT, "ekyc/dev/7/a/cccd-front.jpg",
                EkycEvidenceStage.FINAL, NOW.minus(Duration.ofDays(40)));
        when(evidenceRepository.findLiveByStageCreatedBefore(any(), any(), anyString(), any())).thenReturn(List.of(expired));
        when(evidenceService.deleteObjects(any())).thenReturn(List.of());

        assertThat(sweeper.sweep().deletedObjects()).isZero();
        verify(evidenceRepository, never()).markDeleted(any(), any());
    }

    @Test
    void disabledStorageNeverTouchesObjects() {
        when(evidenceService.isEnabled()).thenReturn(false);

        sweeper.sweep();

        verify(evidenceService, never()).deleteObjects(any());
        verify(evidenceRepository, never()).findLiveByStageCreatedBefore(any(), any(), anyString(), any());
    }

    @Test
    void clearsDraftsOfDecidedAttemptsPastRetention() {
        when(evidenceService.isEnabled()).thenReturn(false);
        EkycAttempt decided = EkycTestData.attempt(9L, UUID.randomUUID(), 7L, EkycDocumentDecision.NEEDS_REVIEW, NOW);
        decided.submitForReview("{\"x\":1}", NOW);
        decided.approve(UUID.randomUUID(), null, NOW.minus(Duration.ofDays(40)));
        when(attemptRepository.findDecidedWithDraftBefore(anyCollection(), any(), any())).thenReturn(List.of(decided));

        assertThat(sweeper.sweep().clearedDrafts()).isEqualTo(1);
        assertThat(decided.getDraftEncrypted()).isNull();
    }
}
