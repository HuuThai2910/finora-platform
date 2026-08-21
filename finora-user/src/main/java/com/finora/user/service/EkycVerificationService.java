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
import com.finora.user.dto.response.EkycResultResponse.EkycDraft;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.support.CryptoUtils;
import com.finora.user.util.CccdMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Xác minh eKYC hai bước: quét ra bản nháp → người dùng soát → xác nhận mới lưu.
 * <p>
 * <b>Bước quét</b> ({@link #verify}): OCR ảnh mặt trước CCCD (Gemini), kiểm tra
 * số CCCD hợp lệ/chưa bị chiếm, rồi cất kết quả vào bản nháp Redis (TTL 10 phút)
 * và trả về cho người dùng soát. <b>Hồ sơ chưa bị đụng tới.</b>
 * <p>
 * <b>Bước xác nhận</b> ({@link #confirm}): đọc bản nháp phía server (client chỉ
 * gửi lệnh đồng ý — không gửi lại dữ liệu để không sửa được), kiểm tra trùng lần
 * cuối rồi mới ghi vào hồ sơ và chuyển {@code VERIFIED}. Người dùng thấy sai
 * thông tin thì bỏ qua bước này và quét lại.
 * <p>
 * Ảnh mặt sau nộp kèm làm bằng chứng cầm thẻ đầy đủ, không OCR.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycVerificationService {

    private final UserProfileRepository userProfileRepository;
    private final AiEkycClient aiEkycClient;
    private final EkycRateLimitService rateLimitService;
    private final EkycDraftStore draftStore;
    private final CryptoProperties cryptoProperties;

    // ── Bước quét: OCR ra bản nháp ─────────────────────────────────

    /**
     * Quét hai mặt CCCD, trả bản nháp thông tin cho người dùng soát.
     * <p>
     * Cố ý <b>không</b> đặt {@code @Transactional}: OCR là lời gọi HTTP sang AI
     * service, giữ transaction trong lúc chờ mạng sẽ giam connection database.
     * Bước này không ghi database.
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

        AiEkycClient.OcrResult ocr;
        try {
            ocr = aiEkycClient.ocr(new AiEkycClient.OcrInput(request.cccdFrontBase64()));
        } catch (Exception e) {
            // AI lỗi không phải lỗi của người dùng: giữ nguyên trạng thái hồ sơ
            // để họ thử lại, tuyệt đối không tự đánh dấu verified hay failed.
            log.error("Không gọi được AI eKYC service: userId={}", profile.getId(), e);
            return reject(profile, EkycResultCode.AI_UNAVAILABLE,
                    "Dịch vụ xác minh đang bận, vui lòng thử lại sau ít phút");
        }

        if (!ocr.success() || ocr.id_number() == null) {
            log.info("OCR không đọc được số CCCD: userId={}, confidence={}",
                    profile.getId(), ocr.confidence());
            return reject(profile, EkycResultCode.OCR_FAILED,
                    "Không đọc được thông tin trên ảnh mặt trước CCCD, vui lòng chụp lại rõ hơn");
        }

        EkycResultResponse invalidId = checkIdNumber(profile, ocr.id_number());
        if (invalidId != null) {
            return invalidId;
        }

        // So trường mềm với dữ liệu người dùng ĐÃ KHAI (nếu có) để cảnh báo sớm
        List<String> warnings = CccdMatcher.softFieldWarnings(
                profile.getFullName(), profile.getDateOfBirth(),
                ocr.full_name(), ocr.date_of_birth());
        if (!warnings.isEmpty()) {
            log.info("Sai lệch trường mềm khi OCR: userId={}, warnings={}",
                    profile.getId(), warnings);
        }

        EkycDraft draft = new EkycDraft(
                ocr.id_number(),
                ocr.full_name() != null ? ocr.full_name().trim() : null,
                ocr.date_of_birth(),
                ocr.gender(),
                ocr.place_of_origin(),
                ocr.address());
        draftStore.save(keycloakUserId, draft);

        log.info("Đã tạo bản nháp eKYC chờ người dùng xác nhận: userId={}", profile.getId());

        return EkycResultResponse.draftReady(
                profile.getEkycStatus(), draft, warnings,
                "Kiểm tra thông tin đọc được từ CCCD rồi xác nhận");
    }

    // ── Bước xác nhận: người dùng đồng ý thì mới lưu ───────────────

    /** Ghi bản nháp đã được người dùng xác nhận vào hồ sơ và chuyển VERIFIED. */
    public EkycResultResponse confirm(UUID keycloakUserId) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);

        if (profile.getEkycStatus() == EkycStatus.VERIFIED) {
            return EkycResultResponse.verified(List.of(), "Hồ sơ đã được xác minh trước đó");
        }

        EkycDraft draft = draftStore.find(keycloakUserId).orElse(null);
        if (draft == null) {
            return reject(profile, EkycResultCode.DRAFT_EXPIRED,
                    "Phiên xác minh đã hết hạn, vui lòng quét lại CCCD");
        }

        // Kiểm tra lại ngay trước khi ghi: số CCCD có thể vừa bị tài khoản khác
        // xác nhận trong lúc bản nháp này còn hiệu lực.
        EkycResultResponse invalidId = checkIdNumber(profile, draft.idNumber());
        if (invalidId != null) {
            draftStore.remove(keycloakUserId);
            return invalidId;
        }

        if (profile.getIdNumberHash() == null || profile.getIdNumberHash().isBlank()) {
            profile.setIdNumberHash(hashId(draft.idNumber()));
            // CryptoConverter tự mã hoá khi JPA persist
            profile.setIdNumberEncrypted(draft.idNumber());
        }
        fillMissingSoftFields(profile, draft);

        profile.markEkycDocumentVerified();
        updateProfileCompleteness(profile);
        userProfileRepository.save(profile);
        draftStore.remove(keycloakUserId);

        log.info("Người dùng xác nhận bản nháp — eKYC hoàn tất: userId={}", profile.getId());

        return EkycResultResponse.verified(List.of(), "Xác minh eKYC thành công");
    }

    // ── Kiểm tra số CCCD (dùng chung hai bước) ─────────────────────

    /**
     * Đối chiếu số CCCD với hồ sơ và toàn hệ thống. Trả response lỗi nếu không
     * hợp lệ, {@code null} nếu qua được.
     */
    private EkycResultResponse checkIdNumber(UserProfile profile, String idNumber) {
        String idHash = hashId(idNumber);

        if (profile.getIdNumberHash() != null && !profile.getIdNumberHash().isBlank()) {
            // Hồ sơ đã có số CCCD (dữ liệu cũ từ luồng khai tay trước đây —
            // luồng đó đã bỏ) — ảnh phải khớp đúng số đó.
            if (!idHash.equals(profile.getIdNumberHash())) {
                log.warn("Số CCCD trên ảnh khác hồ sơ: userId={}", profile.getId());
                return reject(profile, EkycResultCode.ID_MISMATCH,
                        "Số CCCD trên ảnh không khớp thông tin đã khai");
            }
            return null;
        }

        // Mỗi CCCD chỉ được gắn với một tài khoản trên toàn hệ thống.
        if (userProfileRepository.existsByIdNumberHash(idHash)) {
            log.warn("Số CCCD trên ảnh đã thuộc tài khoản khác: userId={}", profile.getId());
            return reject(profile, EkycResultCode.ID_TAKEN,
                    "Số CCCD này đã được đăng ký trong hệ thống");
        }
        return null;
    }

    /**
     * Điền các trường mềm còn trống từ bản nháp: họ tên, ngày sinh, giới tính,
     * quê quán, nơi thường trú. Chỉ điền chỗ trống — không ghi đè dữ liệu đã có.
     */
    private void fillMissingSoftFields(UserProfile profile, EkycDraft draft) {
        if (isBlank(profile.getFullName()) && !isBlank(draft.fullName())) {
            profile.setFullName(draft.fullName());
        }
        if (profile.getDateOfBirth() == null && draft.dateOfBirth() != null) {
            CccdMatcher.parseDate(draft.dateOfBirth()).ifPresent(profile::setDateOfBirth);
        }
        if (profile.getGender() == null) {
            Gender gender = toGender(draft.gender());
            if (gender != null) profile.setGender(gender);
        }
        if (isBlank(profile.getPlaceOfOrigin()) && !isBlank(draft.placeOfOrigin())) {
            profile.setPlaceOfOrigin(draft.placeOfOrigin());
        }
        if (isBlank(profile.getAddress()) && !isBlank(draft.address())) {
            profile.setAddress(draft.address());
        }
    }

    /** Hồ sơ đủ khi có họ tên, số CCCD và số điện thoại. */
    private void updateProfileCompleteness(UserProfile profile) {
        boolean isComplete = profile.getFullName() != null && !profile.getFullName().isBlank()
                && profile.getIdNumberHash() != null && !profile.getIdNumberHash().isBlank()
                && profile.getPhoneHash() != null && !profile.getPhoneHash().isBlank();
        profile.setProfileCompleted(isComplete);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private String hashId(String idNumber) {
        return CryptoUtils.hmacSha256(idNumber, cryptoProperties.getHmacSecret());
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

    private EkycResultResponse reject(UserProfile profile, EkycResultCode code, String message) {
        return EkycResultResponse.rejected(profile.getEkycStatus(), code, message);
    }

    private UserProfile findProfileOrThrow(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }
}
