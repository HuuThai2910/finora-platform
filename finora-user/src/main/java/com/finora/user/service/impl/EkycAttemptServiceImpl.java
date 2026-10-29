package com.finora.user.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.user.domain.CicMappingTask;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycAttemptStatus;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import com.finora.user.domain.UserProfile;
import com.finora.user.repository.CicMappingTaskRepository;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.service.EkycAttemptService;
import com.finora.user.service.EkycEvidenceService.StoredEvidence;
import com.finora.user.service.EkycIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Các bước ghi database của lượt eKYC, dùng chung cho quét thẻ, liveness, xác nhận, duyệt tay và quét dọn.
 * <p>
 * Mọi hàm ghi đều {@link Propagation#MANDATORY}: phải chạy bên trong transaction ngắn do service điều phối
 * mở (qua {@code TransactionTemplate}), sau khi các lời gọi mạng (AI, kho lưu trữ) đã xong. Nhờ vậy
 * không có transaction nào giữ connection trong lúc chờ mạng, và các thay đổi cùng một bất biến
 * (ví dụ hồ sơ VERIFIED + lượt VERIFIED + task CIC) commit hoặc rollback cùng nhau.
 */
@Service
@RequiredArgsConstructor
public class EkycAttemptServiceImpl implements EkycAttemptService {

    private static final TypeReference<List<String>> CODE_LIST = new TypeReference<>() {
    };

    private final EkycAttemptRepository attemptRepository;
    private final EkycEvidenceRepository evidenceRepository;
    private final CicMappingTaskRepository cicMappingTaskRepository;
    private final EkycIdentityService identityService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StartedAttempt start(Long userProfileId, Long consentId, EkycIdentityDraft draft, Instant now) {
        List<EkycAttempt> open = attemptRepository.findByUserProfileIdAndStatusIn(
                userProfileId, List.of(EkycAttemptStatus.IN_PROGRESS));
        open.forEach(attempt -> attempt.abandon(now));
        List<String> staleKeys = open.isEmpty()
                ? List.of()
                : evidenceRepository.findByAttemptIdInAndDeletedAtIsNull(
                        open.stream().map(EkycAttempt::getId).toList()).stream()
                        .map(EkycEvidence::getObjectKey)
                        .toList();
        List<String> codes = new ArrayList<>(new LinkedHashSet<>(draft.documentReasons()));
        draft.documentWarnings().stream().filter(code -> !codes.contains(code)).forEach(codes::add);
        EkycAttempt attempt = attemptRepository.save(EkycAttempt.start(draft.attemptId(), userProfileId, consentId,
                draft.cardType(), draft.documentDecision(), toJson(codes), draft.documentThresholdsVersion(), now));
        return new StartedAttempt(attempt.getId(), staleKeys);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidence(Long attemptId, Collection<StoredEvidence> items, Instant now) {
        if (items.isEmpty()) {
            return;
        }
        evidenceRepository.saveAll(items.stream()
                .map(item -> EkycEvidence.stored(attemptId, item.kind(), item.label(), item.objectKey(),
                        item.sha256(), item.sizeBytes(), item.contentType(), item.stage(), now))
                .toList());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandon(Long attemptId, Instant now) {
        attemptRepository.findById(attemptId).ifPresent(attempt -> attempt.abandon(now));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int markDeleted(Collection<String> objectKeys, Instant now) {
        return objectKeys.isEmpty() ? 0 : evidenceRepository.markDeletedByObjectKeys(objectKeys, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordLiveness(EkycAttempt attempt, EkycLivenessResult result, List<String> reasons,
                               Double faceMatchScore, String thresholdsVersion,
                               Collection<StoredEvidence> evidence, Instant now) {
        if (result != null) {
            attempt.recordLiveness(result, toJson(reasons), faceMatchScore, thresholdsVersion, now);
        }
        recordEvidence(attempt.getId(), evidence, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeVerified(UserProfile profile, EkycAttempt attempt, EkycIdentityDraft draft,
                                 StoredEvidence result, Instant now) {
        identityService.applyIdentity(profile, draft);
        profile.markEkycVerified(draft.faceMatchScore(), true, now);
        attempt.markVerified(now);
        finalizeEvidence(attempt.getId(), now);
        if (result != null) {
            recordEvidence(attempt.getId(), List.of(result), now);
        }
        ensureCicTask(profile.getId(), now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void submitForReview(UserProfile profile, EkycAttempt attempt, EkycIdentityDraft draft,
                                StoredEvidence result, Instant now) {
        profile.markEkycManualReview();
        attempt.submitForReview(draftJson(draft.withoutBiometrics()), now);
        finalizeEvidence(attempt.getId(), now);
        if (result != null) {
            recordEvidence(attempt.getId(), List.of(result), now);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void finalizeEvidence(Long attemptId, Instant now) {
        evidenceRepository.findByAttemptIdOrderByIdAsc(attemptId).forEach(evidence -> evidence.markFinal(now));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureCicTask(Long userProfileId, Instant now) {
        if (cicMappingTaskRepository.findByUserProfileId(userProfileId).isEmpty()) {
            cicMappingTaskRepository.save(CicMappingTask.pending(userProfileId, now));
        }
    }

    @Override
    public String draftJson(EkycIdentityDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được bản nháp eKYC", e);
        }
    }

    @Override
    public EkycIdentityDraft readDraft(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, EkycIdentityDraft.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Override
    public String toJson(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes == null ? List.of() : codes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được danh sách mã lý do", e);
        }
    }

    @Override
    public List<String> readCodes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> codes = objectMapper.readValue(json, CODE_LIST);
            return codes == null ? List.of() : codes;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
