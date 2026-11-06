package com.finora.blockchain.service.proof.impl;

import com.finora.blockchain.config.ProofSubmissionProperties;
import com.finora.blockchain.domain.proof.entity.ProofSubmission;
import com.finora.blockchain.domain.proof.model.ProofIdentity;
import com.finora.blockchain.domain.proof.model.ProofSource;
import com.finora.blockchain.domain.proof.model.ProofSubject;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.service.proof.ProofQueryService;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofSubmissionView;
import com.finora.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Đăng ký proof idempotent theo hai lớp (B53):
 * <ol>
 *   <li>Cùng {@code sourceService + sourceEventId}: Kafka giao trùng, consumer restart → trả bản đã có.</li>
 *   <li>Cùng mốc {@code proofType + aggregateType + aggregateId}: cùng sự kiện nghiệp vụ phát lại với eventId mới →
 *   trả bản đã có nếu cùng hash; khác hash là hai service nguồn không cùng một sự thật → {@code 409}, event vào DLT.</li>
 * </ol>
 * Proof REPAYMENT ghi trước W3D (hash scheme v1) vẫn là bằng chứng của mốc đó; event phát lại không tạo bản v2 thứ hai.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProofSubmissionServiceImpl implements ProofSubmissionService {

    private final ProofSubmissionRepository proofSubmissionRepository;
    private final ProofSubmissionProperties proofSubmissionProperties;
    private final Clock clock;

    @Override
    @Transactional
    public ProofSubmissionView register(ProofRegistrationCommand command) {
        Objects.requireNonNull(command, "command");
        String payloadHash = normalize(command.payloadHash());
        Instant now = clock.instant();
        ProofSubject subject = new ProofSubject(command.proofType(), normalize(command.aggregateType()),
                normalize(command.aggregateId()), command.loanApplicationId());
        ProofSource source = new ProofSource(normalize(command.sourceService()), command.sourceEventId(),
                normalize(command.sourceEventType()), command.sourceEventVersion(), command.sourceOccurredAt());
        if (subject.proofType() == null || subject.aggregateType() == null || subject.aggregateId() == null) {
            throw new IllegalArgumentException("Thiếu proofType/aggregateType/aggregateId");
        }
        ProofSubmission candidate = ProofSubmission.register(
                ProofIdentity.forMilestone(subject.proofType(), subject.aggregateType(), subject.aggregateId()),
                source, subject, payloadHash == null ? null : payloadHash.toLowerCase(Locale.ROOT),
                command.payloadVersion(), proofSubmissionProperties.provider(), now);

        Optional<ProofSubmission> existing = findExisting(candidate);
        if (existing.isEmpty()) {
            proofSubmissionRepository.insertIfAbsent(candidate);
            // Lần chèn có thể bị bỏ qua vì request đồng thời vừa chèn cùng event/mốc: đọc lại bản thắng cuộc.
            existing = findExisting(candidate);
        }
        ProofSubmission stored = existing.orElseThrow(
                () -> new IllegalStateException("Không đọc được proof sau thao tác idempotent insert"));
        return ProofSubmissionView.from(requireCompatible(stored, candidate));
    }

    @Override
    @Transactional
    public ProofSubmissionView requeue(String proofId) {
        ProofSubmission proof = proofSubmissionRepository
                .findByProofIdForUpdate(ProofQueryService.parseProofId(proofId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PROOF_NOT_FOUND", "Không tìm thấy proof"));
        try {
            proof.requeue(clock.instant());
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "PROOF_NOT_DEAD_LETTER",
                    "Chỉ proof đã vào DEAD_LETTER mới được thử lại");
        }
        proofSubmissionRepository.saveAndFlush(proof);
        log.info("Proof được đưa lại hàng đợi: proofId={}, provider={}, lastErrorCode={}",
                proof.getProofId(), proof.getProvider(), proof.getLastErrorCode());
        return ProofSubmissionView.from(proof);
    }

    private Optional<ProofSubmission> findExisting(ProofSubmission candidate) {
        Optional<ProofSubmission> bySource = proofSubmissionRepository.findBySourceServiceAndSourceEventId(
                candidate.getSourceService(), candidate.getSourceEventId());
        if (bySource.isPresent()) {
            return bySource;
        }
        return proofSubmissionRepository.findFirstByProofTypeAndAggregateTypeAndAggregateIdOrderByIdAsc(
                candidate.getProofType(), candidate.getAggregateType(), candidate.getAggregateId());
    }

    private ProofSubmission requireCompatible(ProofSubmission stored, ProofSubmission candidate) {
        if (!stored.sameMilestone(candidate.getProofType(), candidate.getAggregateType(), candidate.getAggregateId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PROOF_SOURCE_EVENT_CONFLICT",
                    "Source event đã được dùng cho một proof có nội dung khác");
        }
        if (stored.sameContent(candidate)) {
            return stored;
        }
        if (stored.getPayloadVersion() < candidate.getPayloadVersion()) {
            // Mốc đã được neo bằng hash scheme cũ; không tạo proof logic thứ hai chỉ vì đổi cách chuẩn hóa.
            log.info("Mốc đã có proof theo hash scheme cũ, giữ nguyên: proofId={}, storedVersion={}, proofType={}",
                    stored.getProofId(), stored.getPayloadVersion(), stored.getProofType());
            return stored;
        }
        throw new BusinessException(HttpStatus.CONFLICT,
                stored.getSourceEventId().equals(candidate.getSourceEventId())
                        ? "PROOF_SOURCE_EVENT_CONFLICT" : "PROOF_MILESTONE_CONFLICT",
                "Mốc nghiệp vụ đã có proof với hash khác");
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
