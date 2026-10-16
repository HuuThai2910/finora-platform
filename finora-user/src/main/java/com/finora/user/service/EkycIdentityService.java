package com.finora.user.service;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.Gender;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycDraftFieldName;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.UserProfile;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.support.CryptoUtils;
import com.finora.user.util.CccdMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Kiểm tra số định danh và ghi thông tin định danh đã được xác nhận vào hồ sơ — dùng chung cho
 * xác nhận tự động và duyệt tay của admin để hai đường ghi hồ sơ giống hệt nhau.
 * <p>
 * Số định danh chỉ so bằng HMAC: bảng hồ sơ không có cột số thô để tra, và HMAC cho phép kiểm tra
 * "mỗi số một tài khoản" mà không giải mã bản ghi nào.
 */
@Service
@RequiredArgsConstructor
public class EkycIdentityService {

    private final UserProfileRepository userProfileRepository;
    private final CryptoProperties cryptoProperties;

    public String hashId(String idNumber) {
        return CryptoUtils.hmacSha256(idNumber, cryptoProperties.getHmacSecret());
    }

    /**
     * Đối chiếu số trên thẻ với hồ sơ và toàn hệ thống.
     *
     * @return {@code ID_MISMATCH} nếu hồ sơ đã có số khác (dữ liệu cũ hoặc lần xác minh trước),
     *         {@code ID_TAKEN} nếu số thuộc tài khoản khác, rỗng nếu hợp lệ
     */
    public Optional<EkycResultCode> checkIdNumber(UserProfile profile, String idNumber) {
        String idHash = hashId(idNumber);
        if (hasText(profile.getIdNumberHash())) {
            return idHash.equals(profile.getIdNumberHash())
                    ? Optional.empty()
                    : Optional.of(EkycResultCode.ID_MISMATCH);
        }
        return userProfileRepository.existsByIdNumberHash(idHash)
                ? Optional.of(EkycResultCode.ID_TAKEN)
                : Optional.empty();
    }

    /**
     * Ghi thông tin đã xác nhận vào hồ sơ; gọi trong transaction. Số định danh lưu hash + bản mã hoá
     * (qua {@code CryptoConverter}). Trường mềm (họ tên, ngày sinh, giới tính, quê quán, địa chỉ) chỉ
     * điền chỗ trống như luồng cũ; trường mô tả tấm thẻ (đời thẻ, ngày cấp, hạn, nơi đăng ký khai sinh)
     * lấy theo thẻ vừa xác minh.
     */
    public void applyIdentity(UserProfile profile, EkycIdentityDraft draft) {
        String idNumber = draft.value(EkycDraftFieldName.ID_NUMBER);
        if (!hasText(profile.getIdNumberHash()) && hasText(idNumber)) {
            profile.setIdNumberHash(hashId(idNumber));
            profile.setIdNumberEncrypted(idNumber);
        }

        String fullName = draft.value(EkycDraftFieldName.FULL_NAME);
        if (!hasText(profile.getFullName()) && hasText(fullName)) {
            profile.setFullName(fullName.trim());
        }
        if (profile.getDateOfBirth() == null) {
            parseDate(draft.value(EkycDraftFieldName.DATE_OF_BIRTH)).ifPresent(profile::setDateOfBirth);
        }
        if (profile.getGender() == null) {
            Gender gender = toGender(draft.value(EkycDraftFieldName.GENDER));
            if (gender != null) {
                profile.setGender(gender);
            }
        }
        String placeOfOrigin = draft.value(EkycDraftFieldName.PLACE_OF_ORIGIN);
        if (!hasText(profile.getPlaceOfOrigin()) && hasText(placeOfOrigin)) {
            profile.setPlaceOfOrigin(placeOfOrigin.trim());
        }
        String address = draft.value(EkycDraftFieldName.ADDRESS);
        if (!hasText(profile.getAddress()) && hasText(address)) {
            profile.setAddress(address.trim());
        }

        profile.setIdCardType(draft.cardType());
        profile.setIdIssueDate(parseDate(draft.value(EkycDraftFieldName.ISSUE_DATE)).orElse(null));
        profile.setIdExpiryDate(draft.noExpiry()
                ? null
                : parseDate(draft.value(EkycDraftFieldName.EXPIRY_DATE)).orElse(null));
        String birthPlace = draft.value(EkycDraftFieldName.BIRTH_REGISTRATION_PLACE);
        if (hasText(birthPlace)) {
            profile.setBirthRegistrationPlace(birthPlace.trim());
        }

        // Hồ sơ đủ khi có họ tên, số định danh và số điện thoại (quy tắc cũ).
        profile.setProfileCompleted(hasText(profile.getFullName())
                && hasText(profile.getIdNumberHash())
                && hasText(profile.getPhoneHash()));
    }

    /** Ngày trên thẻ luôn {@code dd/mm/yyyy}; đọc chặt để ngày không tồn tại không bị nắn thành ngày khác. */
    public static Optional<LocalDate> parseDate(String raw) {
        return CccdMatcher.parseDate(raw);
    }

    /** Giới tính chuẩn hoá về đúng "Nam"/"Nữ"; so sau chuẩn hoá Unicode NFC để không lệch do cách gõ dấu. */
    public static Gender toGender(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if ("Nam".equals(normalized)) {
            return Gender.MALE;
        }
        if ("Nữ".equals(normalized)) {
            return Gender.FEMALE;
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
