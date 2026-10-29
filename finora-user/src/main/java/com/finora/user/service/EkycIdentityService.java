package com.finora.user.service;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.Gender;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.UserProfile;
import com.finora.user.util.CccdMatcher;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Kiểm tra số định danh và ghi thông tin định danh đã được xác nhận vào hồ sơ — dùng chung cho
 * xác nhận tự động và duyệt tay của admin để hai đường ghi hồ sơ giống hệt nhau.
 */
public interface EkycIdentityService {

    String hashId(String idNumber);

    /**
     * Đối chiếu số trên thẻ với hồ sơ và toàn hệ thống.
     *
     * @return {@code ID_MISMATCH} nếu hồ sơ đã có số khác (dữ liệu cũ hoặc lần xác minh trước),
     *         {@code ID_TAKEN} nếu số thuộc tài khoản khác, rỗng nếu hợp lệ
     */
    Optional<EkycResultCode> checkIdNumber(UserProfile profile, String idNumber);

    /**
     * Ghi thông tin đã xác nhận vào hồ sơ; gọi trong transaction. Số định danh lưu hash + bản mã hoá
     * (qua {@code CryptoConverter}). Trường mềm (họ tên, ngày sinh, giới tính, quê quán, địa chỉ) chỉ
     * điền chỗ trống như luồng cũ; trường mô tả tấm thẻ (đời thẻ, ngày cấp, hạn, nơi đăng ký khai sinh)
     * lấy theo thẻ vừa xác minh.
     */
    void applyIdentity(UserProfile profile, EkycIdentityDraft draft);

    /** Ngày trên thẻ luôn {@code dd/mm/yyyy}; đọc chặt để ngày không tồn tại không bị nắn thành ngày khác. */
    static Optional<LocalDate> parseDate(String raw) {
        return CccdMatcher.parseDate(raw);
    }

    /** Giới tính chuẩn hoá về đúng "Nam"/"Nữ"; so sau chuẩn hoá Unicode NFC để không lệch do cách gõ dấu. */
    static Gender toGender(String raw) {
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
}
