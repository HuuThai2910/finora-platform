package com.finora.user.service;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.client.AiEkycClient;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.LivenessAction;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.LivenessChallengeResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.util.CccdMatcher;
import com.finora.user.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Xác minh eKYC theo luồng F01 — User là orchestrator, AI chỉ cung cấp bằng chứng.
 * <p>
 * Một lần xác minh chạy lần lượt và <b>dừng ngay ở bước đầu tiên thất bại</b> để
 * không tốn các bước sau (OCR, FaceMesh và nhận dạng khuôn mặt đều nặng):
 * <ol>
 *   <li>Hồ sơ phải có số CCCD — số CCCD là điều kiện chặn duy nhất.</li>
 *   <li>Phiên challenge còn hiệu lực và chưa dùng.</li>
 *   <li>OCR ảnh CCCD, đối chiếu số CCCD bằng HMAC.</li>
 *   <li>Active liveness đúng chuỗi hành động của phiên.</li>
 *   <li>So khớp khuôn mặt giữa frame tốt nhất và ảnh CCCD.</li>
 * </ol>
 * Mọi thất bại đều cho gửi lại: trạng thái hồ sơ giữ nguyên, người dùng chụp lại
 * và lấy challenge mới. Chỉ khi sai khuôn mặt liên tiếp nhiều lần mới gắn cờ
 * {@link EkycStatus#MANUAL_REVIEW} cho admin xem — vẫn không khoá người dùng.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycVerificationService {

    private final UserProfileRepository userProfileRepository;
    private final AiEkycClient aiEkycClient;
    private final EkycChallengeService challengeService;
    private final CryptoProperties cryptoProperties;

    /** Cấp thử thách mới cho phiên xác minh sắp tới. */
    public LivenessChallengeResponse createLivenessChallenge(UUID keycloakUserId) {
        findProfileOrThrow(keycloakUserId);
        return challengeService.createChallenge(keycloakUserId);
    }

    /**
     * Xác minh eKYC.
     * <p>
     * Cố ý <b>không</b> đặt {@code @Transactional} trên phương thức này: luồng có
     * ba lần gọi HTTP sang AI service, giữ transaction trong lúc chờ mạng sẽ
     * giam connection database hàng chục giây. Trạng thái chỉ được ghi bằng một
     * lệnh {@code save} ở cuối, bản thân nó đã là một transaction ngắn.
     */
    public EkycResultResponse verify(UUID keycloakUserId, EkycVerifyRequest request) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);

        // Kiểm tra rẻ tiền đặt trước, để không tiêu suất rate limit vào những
        // lần gọi chắc chắn không cần tới AI.
        if (profile.getEkycStatus() == EkycStatus.VERIFIED) {
            return EkycResultResponse.verified(
                    orZero(profile.getFaceMatchScore()), List.of(), "Hồ sơ đã được xác minh trước đó");
        }
        if (profile.getIdNumberHash() == null || profile.getIdNumberHash().isBlank()) {
            return reject(profile, EkycResultCode.PROFILE_NO_CCCD,
                    "Vui lòng nhập thông tin CCCD trước khi xác minh");
        }
        if (!challengeService.tryAcquireVerifySlot(keycloakUserId)) {
            return reject(profile, EkycResultCode.RATE_LIMITED,
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau "
                            + EkycChallengeService.VERIFY_MIN_INTERVAL.toSeconds() + " giây");
        }

        Optional<List<LivenessAction>> actions =
                challengeService.consumeChallenge(keycloakUserId, request.sessionId());
        if (actions.isEmpty()) {
            return reject(profile, EkycResultCode.CHALLENGE_EXPIRED,
                    "Phiên xác minh đã hết hạn, vui lòng bắt đầu lại");
        }

        try {
            return runVerification(profile, request, actions.get());
        } catch (Exception e) {
            // AI lỗi không phải lỗi của người dùng: giữ nguyên trạng thái hồ sơ
            // để họ thử lại, tuyệt đối không tự đánh dấu verified hay failed.
            log.error("Không gọi được AI eKYC service: userId={}", profile.getId(), e);
            return reject(profile, EkycResultCode.AI_UNAVAILABLE,
                    "Dịch vụ xác minh đang bận, vui lòng thử lại sau ít phút");
        }
    }

    // ── Các bước xác minh ───────────────────────────────────────────

    private EkycResultResponse runVerification(
            UserProfile profile, EkycVerifyRequest request, List<LivenessAction> actions) {

        AiEkycClient.OcrResult ocr = aiEkycClient.ocr(
                new AiEkycClient.OcrInput(request.cccdImageBase64()));

        if (!ocr.success() || ocr.id_number() == null) {
            log.info("OCR không đọc được số CCCD: userId={}, confidence={}",
                    profile.getId(), ocr.confidence());
            return reject(profile, EkycResultCode.OCR_FAILED,
                    "Không đọc được thông tin trên ảnh CCCD, vui lòng chụp lại rõ hơn");
        }

        String ocrIdHash = CryptoUtils.hmacSha256(ocr.id_number(), cryptoProperties.hmacSecret());
        if (!ocrIdHash.equals(profile.getIdNumberHash())) {
            log.warn("Số CCCD trên ảnh khác hồ sơ: userId={}", profile.getId());
            return reject(profile, EkycResultCode.ID_MISMATCH,
                    "Số CCCD trên ảnh không khớp thông tin đã khai");
        }

        List<String> warnings = CccdMatcher.softFieldWarnings(
                profile.getFullName(), profile.getDateOfBirth(),
                ocr.full_name(), ocr.date_of_birth());
        if (!warnings.isEmpty()) {
            log.info("Sai lệch trường mềm khi OCR: userId={}, warnings={}",
                    profile.getId(), warnings);
        }

        AiEkycClient.ActiveLivenessResult liveness = aiEkycClient.activeLiveness(
                new AiEkycClient.ActiveLivenessInput(
                        request.frames(), LivenessAction.toWireValues(actions)));

        if (!liveness.is_live()) {
            log.info("Liveness không đạt: userId={}, evidence={}",
                    profile.getId(), describeFailedActions(liveness));
            return reject(profile, EkycResultCode.LIVENESS_FAILED,
                    "Chưa thực hiện đúng động tác yêu cầu, vui lòng thử lại");
        }

        AiEkycClient.FaceMatchResult face = aiEkycClient.faceMatch(
                new AiEkycClient.FaceMatchInput(
                        selectFrame(request.frames(), liveness.best_frame_index()),
                        request.cccdImageBase64()));

        if (!face.match()) {
            return handleFaceMismatch(profile, face, warnings);
        }

        challengeService.resetFaceMismatch(profile.getKeycloakUserId());
        profile.markEkycVerified(face.similarity());
        userProfileRepository.save(profile);

        log.info("Xác minh eKYC thành công: userId={}, similarity={}, warnings={}",
                profile.getId(), face.similarity(), warnings);

        return EkycResultResponse.verified(face.similarity(), warnings, "Xác minh eKYC thành công");
    }

    /**
     * Sai khuôn mặt: vẫn cho thử lại, nhưng sai liên tiếp nhiều lần là dấu hiệu
     * người cầm CCCD không phải chủ thẻ nên gắn cờ cho admin xem.
     */
    private EkycResultResponse handleFaceMismatch(
            UserProfile profile, AiEkycClient.FaceMatchResult face, List<String> warnings) {

        int failures = challengeService.recordFaceMismatch(profile.getKeycloakUserId());
        log.warn("Khuôn mặt không khớp CCCD: userId={}, similarity={}, lần thứ {}",
                profile.getId(), face.similarity(), failures);

        if (failures >= EkycChallengeService.MAX_FACE_FAILURES
                && profile.getEkycStatus() != EkycStatus.MANUAL_REVIEW) {
            profile.markEkycManualReview();
            userProfileRepository.save(profile);
            return EkycResultResponse.faceRejected(
                    EkycStatus.MANUAL_REVIEW, face.similarity(), warnings,
                    "Khuôn mặt không khớp nhiều lần, hồ sơ đã được chuyển cho nhân viên xem xét");
        }

        return EkycResultResponse.faceRejected(
                profile.getEkycStatus(), face.similarity(), warnings,
                "Khuôn mặt không khớp với ảnh trên CCCD, vui lòng thử lại");
    }

    // ── Helper ──────────────────────────────────────────────────────

    /**
     * Lấy frame mà AI đánh giá là tốt nhất để so khớp khuôn mặt. Chỉ số ngoài
     * phạm vi thì rơi về frame đầu — chỉ mất độ chính xác, không hỏng luồng.
     */
    private String selectFrame(List<String> frames, Integer bestFrameIndex) {
        if (bestFrameIndex == null || bestFrameIndex < 0 || bestFrameIndex >= frames.size()) {
            log.warn("best_frame_index không hợp lệ ({}), dùng frame đầu tiên", bestFrameIndex);
            return frames.get(0);
        }
        return frames.get(bestFrameIndex);
    }

    private String describeFailedActions(AiEkycClient.ActiveLivenessResult liveness) {
        if (liveness.actions() == null) {
            return "không có chi tiết";
        }
        return liveness.actions().stream()
                .filter(action -> !action.passed())
                .map(action -> action.action() + ": " + action.evidence())
                .reduce((a, b) -> a + "; " + b)
                .orElse("không có chi tiết");
    }

    private EkycResultResponse reject(UserProfile profile, EkycResultCode code, String message) {
        return EkycResultResponse.rejected(profile.getEkycStatus(), code, message);
    }

    private double orZero(Double value) {
        return value != null ? value : 0.0;
    }

    private UserProfile findProfileOrThrow(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }
}
