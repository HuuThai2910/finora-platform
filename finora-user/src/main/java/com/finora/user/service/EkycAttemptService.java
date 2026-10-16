package com.finora.user.service;

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
import com.finora.user.service.EkycEvidenceService.StoredEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
public class EkycAttemptService {

    private static final TypeReference<List<String>> CODE_LIST = new TypeReference<>() {
    };

    private final EkycAttemptRepository attemptRepository;
    private final EkycEvidenceRepository evidenceRepository;
    private final CicMappingTaskRepository cicMappingTaskRepository;
    private final EkycIdentityService identityService;
    private final ObjectMapper objectMapper;

    /**
     * Huỷ lượt đang mở cũ (quét lại) và mở lượt mới cho bản nháp vừa đọc.
     *
     * @return ID lượt mới và object key còn sống của lượt cũ — xoá trên kho sau khi commit
     */
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

    /** Upload lỗi hoặc không còn dùng được: bỏ lượt; object (nếu có) do worker quét dọn xoá tiếp. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandon(Long attemptId, Instant now) {
        attemptRepository.findById(attemptId).ifPresent(attempt -> attempt.abandon(now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int markDeleted(Collection<String> objectKeys, Instant now) {
        return objectKeys.isEmpty() ? 0 : evidenceRepository.markDeletedByObjectKeys(objectKeys, now);
    }

    /**
     * Ghi kết quả một lần gửi video: bằng chứng mới luôn được lưu; kết luận chỉ ghi khi AI có kết luận
     * ({@code result != null}) — video phải quay lại không ghi đè kết luận trước.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordLiveness(EkycAttempt attempt, EkycLivenessResult result, List<String> reasons,
                               Double faceMatchScore, String thresholdsVersion,
                               Collection<StoredEvidence> evidence, Instant now) {
        if (result != null) {
            attempt.recordLiveness(result, toJson(reasons), faceMatchScore, thresholdsVersion, now);
        }
        recordEvidence(attempt.getId(), evidence, now);
    }

    /**
     * Xác minh tự động: ghi định danh vào hồ sơ, hồ sơ + lượt VERIFIED, bằng chứng sang {@code final},
     * thêm {@code result.json} và task ánh xạ CIC — cùng một transaction.
     */
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

    /**
     * Gửi duyệt tay: hồ sơ + lượt MANUAL_REVIEW, bản nháp (đã bỏ embedding) mã hoá vào lượt để nhân viên
     * duyệt sau này. Trường định danh chưa được ghi vào hồ sơ.
     */
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

    /** Bằng chứng còn sống của lượt chuyển sang giai đoạn giữ theo hạn lưu. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finalizeEvidence(Long attemptId, Instant now) {
        evidenceRepository.findByAttemptIdOrderByIdAsc(attemptId).forEach(evidence -> evidence.markFinal(now));
    }

    /** Mỗi hồ sơ một task ánh xạ CIC; xác minh lại sau khi rút đồng ý không tạo task thứ hai. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureCicTask(Long userProfileId, Instant now) {
        if (cicMappingTaskRepository.findByUserProfileId(userProfileId).isEmpty()) {
            cicMappingTaskRepository.save(CicMappingTask.pending(userProfileId, now));
        }
    }

    public String draftJson(EkycIdentityDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được bản nháp eKYC", e);
        }
    }

    /** Đọc bản nháp đã lưu ở lượt; {@code null} nếu không có hoặc không đọc được. */
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

    public String toJson(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes == null ? List.of() : codes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được danh sách mã lý do", e);
        }
    }

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

    /**
     * Vùng so khớp khuôn mặt suy từ mã lý do đã lưu: {@code FACE_MATCH_HIGH} → HIGH,
     * {@code FACE_MATCH_GRAY} → GRAY, {@code FACE_MISMATCH} → LOW; chưa so mặt → {@code null}.
     */
    public static String faceMatchZone(Collection<String> livenessReasons) {
        Set<String> codes = livenessReasons == null ? Set.of() : Set.copyOf(livenessReasons);
        if (codes.contains("FACE_MISMATCH")) {
            return "LOW";
        }
        if (codes.contains("FACE_MATCH_GRAY")) {
            return "GRAY";
        }
        if (codes.contains("FACE_MATCH_HIGH")) {
            return "HIGH";
        }
        return null;
    }

    /** Mã lý do tương ứng với vùng AI trả, để lưu kèm khi AI không tự đưa mã đó vào {@code reasons}. */
    public static String zoneReasonCode(String zone) {
        if (zone == null) {
            return null;
        }
        return switch (zone) {
            case "HIGH" -> "FACE_MATCH_HIGH";
            case "GRAY" -> "FACE_MATCH_GRAY";
            case "LOW" -> "FACE_MISMATCH";
            default -> null;
        };
    }

    /** Lượt vừa mở và object key của lượt cũ cần xoá. */
    public record StartedAttempt(Long attemptId, List<String> staleObjectKeys) {
    }
}
