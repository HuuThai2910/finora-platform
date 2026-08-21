package com.finora.user.dto.response;

import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;

import java.util.List;

/**
 * Kết quả một lần xác minh eKYC.
 *
 * @param status      trạng thái hồ sơ sau lần gọi này
 * @param resultCode  lần gọi dừng ở bước nào — client dựa vào đây để hướng dẫn chụp lại
 * @param ocrWarnings các trường mềm lệch so với hồ sơ (họ tên, ngày sinh) — không chặn
 * @param message     thông điệp hiển thị cho người dùng
 */
public record EkycResultResponse(
        EkycStatus status,
        EkycResultCode resultCode,
        List<String> ocrWarnings,
        String message
) {

    /** Kết quả không đạt — hồ sơ giữ nguyên trạng thái hiện tại. */
    public static EkycResultResponse rejected(
            EkycStatus status, EkycResultCode resultCode, String message) {
        return new EkycResultResponse(status, resultCode, List.of(), message);
    }

    /** Đạt toàn bộ các bước. */
    public static EkycResultResponse verified(List<String> ocrWarnings, String message) {
        return new EkycResultResponse(
                EkycStatus.VERIFIED, EkycResultCode.VERIFIED, ocrWarnings, message);
    }
}
