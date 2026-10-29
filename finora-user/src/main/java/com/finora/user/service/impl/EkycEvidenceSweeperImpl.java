package com.finora.user.service.impl;

import com.finora.user.config.EkycStorageProperties;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycAttemptStatus;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.service.EkycEvidenceSweeper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Worker quét dọn bằng chứng eKYC (mặc định mỗi giờ, tắt bằng {@code EKYC_SWEEPER_ENABLED=false}).
 * <ol>
 *   <li>Lượt {@code IN_PROGRESS} quá 24 giờ → {@code ABANDONED} (người dùng bỏ dở).</li>
 *   <li>Object của lượt {@code ABANDONED} còn trên kho → xoá (xoá đồng bộ lần trước có thể đã lỗi).</li>
 *   <li>Object {@code final} quá {@code EKYC_EVIDENCE_RETENTION_DAYS} ngày → xoá; bản nháp mã hoá của
 *       lượt đã duyệt/từ chối quá hạn → xoá.</li>
 *   <li>Object tạo trước một lần rút đồng ý của chính chủ → xoá (lần rút đó xoá theo tiền tố bị lỗi).</li>
 * </ol>
 * Không dựa vào vòng đời của bucket (chỉ là lớp phòng thủ thêm, SeaweedFS chưa chắc thực thi lọc theo tag).
 * Mỗi instance chỉ xoá object trong tiền tố {@code ekyc/<env>/} của mình vì nhiều môi trường có thể dùng
 * chung database. Object xoá lỗi giữ {@code deleted_at = null} để vòng sau thử lại; mỗi vòng giới hạn số
 * lô để không chiếm worker quá lâu.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.ekyc.sweeper", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EkycEvidenceSweeperImpl implements EkycEvidenceSweeper {

    static final Duration ABANDON_AFTER = Duration.ofHours(24);
    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES = 10;

    private final EkycAttemptRepository attemptRepository;
    private final EkycEvidenceRepository evidenceRepository;
    private final EkycEvidenceService evidenceService;
    private final EkycStorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${finora.ekyc.sweeper.interval-ms:3600000}",
            initialDelayString = "${finora.ekyc.sweeper.initial-delay-ms:300000}")
    @Override
    public void run() {
        try {
            SweepReport report = sweep();
            if (report.abandonedAttempts() + report.deletedObjects() + report.clearedDrafts() > 0) {
                log.info("Quét dọn eKYC: abandonedAttempts={}, deletedObjects={}, clearedDrafts={}",
                        report.abandonedAttempts(), report.deletedObjects(), report.clearedDrafts());
            }
        } catch (RuntimeException e) {
            // Vòng sau chạy lại toàn bộ; không log message vì có thể chứa key/SQL.
            log.warn("Vòng quét dọn eKYC lỗi, sẽ thử lại ở vòng sau: cause={}", e.getClass().getSimpleName());
        }
    }

    @Override
    public SweepReport sweep() {
        Instant now = clock.instant();
        Instant retentionCutoff = now.minus(Duration.ofDays(Math.max(1, storageProperties.getRetentionDays())));
        int abandoned = abandonStaleAttempts(now);
        int deleted = 0;
        if (evidenceService.isEnabled()) {
            String prefix = evidenceService.envPrefix() + "%";
            deleted += deleteInBatches(pageable -> evidenceRepository.findLiveOfAttemptsInStatus(
                    EkycAttemptStatus.ABANDONED, prefix, pageable), now);
            deleted += deleteInBatches(pageable -> evidenceRepository.findLiveByStageCreatedBefore(
                    EkycEvidenceStage.FINAL, retentionCutoff, prefix, pageable), now);
            deleted += deleteInBatches(pageable -> evidenceRepository.findLiveCreatedBeforeWithdrawal(
                    prefix, pageable), now);
        }
        int clearedDrafts = clearExpiredDrafts(retentionCutoff, now);
        return new SweepReport(abandoned, deleted, clearedDrafts);
    }

    private int abandonStaleAttempts(Instant now) {
        Instant cutoff = now.minus(ABANDON_AFTER);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Integer count = transactionTemplate.execute(tx -> {
                List<EkycAttempt> stale = attemptRepository.findByStatusCreatedBefore(
                        EkycAttemptStatus.IN_PROGRESS, cutoff, PageRequest.of(0, BATCH_SIZE));
                stale.forEach(attempt -> attempt.abandon(now));
                return stale.size();
            });
            int abandoned = count == null ? 0 : count;
            total += abandoned;
            if (abandoned < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /** Xoá từng lô: gọi kho ngoài transaction, rồi đánh dấu {@code deleted_at} cho đúng object đã xoá. */
    private int deleteInBatches(Function<Pageable, List<EkycEvidence>> finder, Instant now) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<EkycEvidence> page = finder.apply(PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) {
                break;
            }
            List<String> deletedKeys = evidenceService.deleteObjects(
                    page.stream().map(EkycEvidence::getObjectKey).toList());
            Set<String> deletedSet = Set.copyOf(deletedKeys);
            List<Long> ids = page.stream()
                    .filter(evidence -> deletedSet.contains(evidence.getObjectKey()))
                    .map(EkycEvidence::getId)
                    .collect(Collectors.toList());
            if (!ids.isEmpty()) {
                transactionTemplate.executeWithoutResult(tx -> evidenceRepository.markDeleted(ids, now));
            }
            total += ids.size();
            // Có object xoá lỗi (kho trục trặc) hoặc đã hết dữ liệu: dừng, vòng sau thử lại.
            if (ids.size() < page.size() || page.size() < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private int clearExpiredDrafts(Instant cutoff, Instant now) {
        Integer count = transactionTemplate.execute(tx -> {
            List<EkycAttempt> decided = attemptRepository.findDecidedWithDraftBefore(
                    List.of(EkycAttemptStatus.APPROVED, EkycAttemptStatus.REJECTED), cutoff,
                    PageRequest.of(0, BATCH_SIZE));
            decided.forEach(attempt -> attempt.clearDraft(now));
            return decided.size();
        });
        return count == null ? 0 : count;
    }
}
