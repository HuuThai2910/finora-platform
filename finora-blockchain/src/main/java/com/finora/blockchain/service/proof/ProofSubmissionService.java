package com.finora.blockchain.service.proof;

import com.finora.blockchain.config.ProofSubmissionProperties;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đăng ký proof idempotent theo cặp sourceService/sourceEventId. */
@Service
@RequiredArgsConstructor
public class ProofSubmissionService {

    private final ProofSubmissionRepository repository;
    private final ProofSubmissionProperties properties;
    private final Clock clock;

    @Transactional
    public ProofSubmissionView register(ProofRegistrationCommand command) {
        Objects.requireNonNull(command, "command");
        String sourceService = normalize(command.sourceService());
        String aggregateType = normalize(command.aggregateType());
        String aggregateId = normalize(command.aggregateId());
        String normalizedHash = normalize(command.payloadHash());
        String payloadHash = normalizedHash == null ? null : normalizedHash.toLowerCase(Locale.ROOT);

        Instant now = clock.instant();
        ProofSubmission candidate = ProofSubmission.register(
                UUID.randomUUID(), sourceService, command.sourceEventId(), aggregateType, aggregateId,
                command.proofType(), payloadHash, command.payloadVersion(), properties.provider(), now);
        repository.insertIfAbsent(
                candidate.getProofId(), candidate.getSourceService(), candidate.getSourceEventId(),
                candidate.getAggregateType(), candidate.getAggregateId(), candidate.getProofType().name(),
                candidate.getPayloadHash(), candidate.getPayloadVersion(), candidate.getProvider().name(), now);

        ProofSubmission stored = repository
                .findBySourceServiceAndSourceEventId(sourceService, command.sourceEventId())
                .orElseThrow(() -> new IllegalStateException("Không đọc được proof sau thao tác idempotent insert"));
        if (!stored.matches(aggregateType, aggregateId, command.proofType(), payloadHash, command.payloadVersion())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "PROOF_SOURCE_EVENT_CONFLICT",
                    "Source event đã được dùng cho một proof có nội dung khác"
            );
        }
        return ProofSubmissionView.from(stored);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
