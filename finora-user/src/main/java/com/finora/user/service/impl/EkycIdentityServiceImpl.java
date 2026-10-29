package com.finora.user.service.impl;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.Gender;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycDraftFieldName;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.UserProfile;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycIdentityService;
import com.finora.user.support.CryptoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
public class EkycIdentityServiceImpl implements EkycIdentityService {

    private final UserProfileRepository userProfileRepository;
    private final CryptoProperties cryptoProperties;

    @Override
    public String hashId(String idNumber) {
        return CryptoUtils.hmacSha256(idNumber, cryptoProperties.getHmacSecret());
    }

    @Override
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

    @Override
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
            EkycIdentityService.parseDate(draft.value(EkycDraftFieldName.DATE_OF_BIRTH)).ifPresent(profile::setDateOfBirth);
        }
        if (profile.getGender() == null) {
            Gender gender = EkycIdentityService.toGender(draft.value(EkycDraftFieldName.GENDER));
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
        profile.setIdIssueDate(EkycIdentityService.parseDate(draft.value(EkycDraftFieldName.ISSUE_DATE)).orElse(null));
        profile.setIdExpiryDate(draft.noExpiry()
                ? null
                : EkycIdentityService.parseDate(draft.value(EkycDraftFieldName.EXPIRY_DATE)).orElse(null));
        String birthPlace = draft.value(EkycDraftFieldName.BIRTH_REGISTRATION_PLACE);
        if (hasText(birthPlace)) {
            profile.setBirthRegistrationPlace(birthPlace.trim());
        }

        // Hồ sơ đủ khi có họ tên, số định danh và số điện thoại (quy tắc cũ).
        profile.setProfileCompleted(hasText(profile.getFullName())
                && hasText(profile.getIdNumberHash())
                && hasText(profile.getPhoneHash()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
