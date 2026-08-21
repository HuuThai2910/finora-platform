package com.finora.user.service;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.client.AiEkycClient;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.Gender;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.support.CryptoUtils;
import com.finora.user.util.CccdMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Xác minh eKYC bằng ảnh giấy tờ — User là orchestrator, AI chỉ cung cấp bằng chứng.
 * <p>
 * Luồng đã bỏ xác minh khuôn mặt/liveness theo quyết định thiết kế: người dùng
 * chụp <b>hai mặt CCCD</b>, backend OCR mặt trước để lấy số CCCD, còn mặt sau
 * nộp kèm làm bằng chứng cầm thẻ đầy đủ (không OCR — model chỉ đọc mặt trước,
 * ép đọc mặt sau sẽ trượt oan). Một lần xác minh chạy lần lượt:
 * <ol>
 *   <li>Chặn gọi dồn dập (OCR tốn tài nguyên).</li>
 *   <li>OCR ảnh mặt trước — không đọc được số thì yêu cầu chụp lại.</li>
 *   <li>Đối chiếu số CCCD với hồ sơ bằng HMAC. Hồ sơ chưa có số thì lấy số từ
 *       OCR điền vào, sau khi kiểm tra số đó chưa thuộc tài khoản khác.</li>
 *   <li>So trường mềm (họ tên, ngày sinh) — chỉ cảnh báo, không chặn.</li>
 * </ol>
 * Mọi thất bại đều cho gửi lại: trạng thái hồ sơ giữ nguyên và người dùng chụp lại.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycVerificationService {

    private final UserProfileRepository userProfileRepository;
    private final AiEkycClient aiEkycClient;
    private final EkycRateLimitService rateLimitService;
    private final CryptoProperties cryptoProperties;

    /**
     * Xác minh eKYC.
     * <p>
     * Cố ý <b>không</b> đặt {@code @Transactional} trên phương thức này: OCR là
     * lời gọi HTTP sang AI service, giữ transaction trong lúc chờ mạng sẽ giam
     * connection database hàng chục giây. Trạng thái chỉ được ghi bằng một lệnh
     * {@code save} ở cuối, bản thân nó đã là một transaction ngắn.
     */
    public EkycResultResponse verify(UUID keycloakUserId, EkycVerifyRequest request) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);

        // Kiểm tra rẻ tiền đặt trước, để không tiêu suất rate limit vào những
        // lần gọi chắc chắn không cần tới AI.
        if (profile.getEkycStatus() == EkycStatus.VERIFIED) {
            return EkycResultResponse.verified(List.of(), "Hồ sơ đã được xác minh trước đó");
        }
        if (!rateLimitService.tryAcquireVerifySlot(keycloakUserId)) {
            return reject(profile, EkycResultCode.RATE_LIMITED,
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau "
                            + EkycRateLimitService.VERIFY_MIN_INTERVAL.toSeconds() + " giây");
        }

        try {
            return runVerification(profile, request);
        } catch (Exception e) {
            // AI lỗi không phải lỗi của người dùng: giữ nguyên trạng thái hồ sơ
            // để họ thử lại, tuyệt đối không tự đánh dấu verified hay failed.
            log.error("Không gọi được AI eKYC service: userId={}", profile.getId(), e);
            return reject(profile, EkycResultCode.AI_UNAVAILABLE,
                    "Dịch vụ xác minh đang bận, vui lòng thử lại sau ít phút");
        }
    }

    // ── Các bước xác minh ───────────────────────────────────────────

    private EkycResultResponse runVerification(UserProfile profile, EkycVerifyRequest request) {
        AiEkycClient.OcrResult ocr = aiEkycClient.ocr(
                new AiEkycClient.OcrInput(request.cccdFrontBase64()));

        if (!ocr.success() || ocr.id_number() == null) {
            log.info("OCR không đọc được số CCCD: userId={}, confidence={}",
                    profile.getId(), ocr.confidence());
            return reject(profile, EkycResultCode.OCR_FAILED,
                    "Không đọc được thông tin trên ảnh mặt trước CCCD, vui lòng chụp lại rõ hơn");
        }

        String ocrIdHash = CryptoUtils.hmacSha256(ocr.id_number(), cryptoProperties.getHmacSecret());

        // So trường mềm với dữ liệu người dùng ĐÃ KHAI, phải tính trước khi
        // điền từ OCR — điền xong mới so thì mọi thứ luôn khớp và cảnh báo mất tác dụng.
        List<String> warnings = CccdMatcher.softFieldWarnings(
                profile.getFullName(), profile.getDateOfBirth(),
                ocr.full_name(), ocr.date_of_birth());
        if (!warnings.isEmpty()) {
            log.info("Sai lệch trường mềm khi OCR: userId={}, warnings={}",
                    profile.getId(), warnings);
        }

        if (profile.getIdNumberHash() != null && !profile.getIdNumberHash().isBlank()) {
            // Hồ sơ đã khai số CCCD — ảnh phải khớp đúng số đó.
            if (!ocrIdHash.equals(profile.getIdNumberHash())) {
                log.warn("Số CCCD trên ảnh khác hồ sơ: userId={}", profile.getId());
                return reject(profile, EkycResultCode.ID_MISMATCH,
                        "Số CCCD trên ảnh không khớp thông tin đã khai");
            }
        } else {
            // Hồ sơ chưa có số CCCD — lấy số từ OCR, nhưng mỗi CCCD chỉ được
            // gắn với một tài khoản trên toàn hệ thống.
            if (userProfileRepository.existsByIdNumberHash(ocrIdHash)) {
                log.warn("Số CCCD trên ảnh đã thuộc tài khoản khác: userId={}", profile.getId());
                return reject(profile, EkycResultCode.ID_TAKEN,
                        "Số CCCD này đã được đăng ký trong hệ thống");
            }
            profile.setIdNumberHash(ocrIdHash);
            // CryptoConverter tự mã hoá khi JPA persist
            profile.setIdNumberEncrypted(ocr.id_number());
        }

        fillMissingSoftFields(profile, ocr);

        profile.markEkycDocumentVerified();
        updateProfileCompleteness(profile);
        userProfileRepository.save(profile);

        log.info("Xác minh eKYC (giấy tờ hai mặt) thành công: userId={}, warnings={}",
                profile.getId(), warnings);

        return EkycResultResponse.verified(warnings, "Xác minh eKYC thành công");
    }

    /**
     * Điền các trường mềm còn trống từ OCR: họ tên, ngày sinh, giới tính,
     * quê quán, nơi thường trú. Chỉ điền chỗ trống — không bao giờ ghi đè dữ
     * liệu người dùng đã khai, vì dữ liệu khai tay là thứ họ đã xác nhận.
     * <p>
     * Họ tên là đường điền chính: đăng ký không thu họ tên nữa, hồ sơ chỉ có
     * tên sau khi quét CCCD.
     */
    private void fillMissingSoftFields(UserProfile profile, AiEkycClient.OcrResult ocr) {
        if (isBlank(profile.getFullName()) && !isBlank(ocr.full_name())) {
            profile.setFullName(ocr.full_name());
        }
        if (profile.getDateOfBirth() == null && ocr.date_of_birth() != null) {
            CccdMatcher.parseDate(ocr.date_of_birth()).ifPresent(profile::setDateOfBirth);
        }
        if (profile.getGender() == null) {
            Gender gender = toGender(ocr.gender());
            if (gender != null) profile.setGender(gender);
        }
        if (isBlank(profile.getPlaceOfOrigin()) && !isBlank(ocr.place_of_origin())) {
            profile.setPlaceOfOrigin(ocr.place_of_origin());
        }
        if (isBlank(profile.getAddress()) && !isBlank(ocr.address())) {
            profile.setAddress(ocr.address());
        }
    }

    /** OCR chuẩn hoá giới tính về đúng hai giá trị "Nam"/"Nữ"; giá trị lạ bỏ qua. */
    private static Gender toGender(String raw) {
        if ("Nam".equals(raw)) return Gender.MALE;
        if ("Nữ".equals(raw)) return Gender.FEMALE;
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Cùng luật với {@code UserProfileServiceImpl}: hồ sơ đủ khi có họ tên,
     * số CCCD và số điện thoại.
     */
    private void updateProfileCompleteness(UserProfile profile) {
        boolean isComplete = profile.getFullName() != null && !profile.getFullName().isBlank()
                && profile.getIdNumberHash() != null && !profile.getIdNumberHash().isBlank()
                && profile.getPhoneHash() != null && !profile.getPhoneHash().isBlank();
        profile.setProfileCompleted(isComplete);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private EkycResultResponse reject(UserProfile profile, EkycResultCode code, String message) {
        return EkycResultResponse.rejected(profile.getEkycStatus(), code, message);
    }

    private UserProfile findProfileOrThrow(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }
}
