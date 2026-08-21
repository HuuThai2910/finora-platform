package com.finora.user.dto.response;

import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;

import java.util.List;

/**
 * Kết quả một lần xác minh eKYC.
 *
 * @param status            trạng thái hồ sơ sau lần gọi này
 * @param resultCode        lần gọi dừng ở bước nào — client dựa vào đây để hướng dẫn chụp lại
 * @param faceMatch         khuôn mặt có khớp ảnh trên CCCD không
 * @param faceMatchScore    độ tương đồng khuôn mặt (0-1)
 * @param livenessVerified  đã vượt qua kiểm tra người thật chưa
 * @param ocrWarnings       các trường mềm lệch so với hồ sơ (họ tên, ngày sinh) — không chặn
 * @param message           thông điệp hiển thị cho người dùng
 */
public record EkycResultResponse(
        EkycStatus status,
        EkycResultCode resultCode,
        boolean faceMatch,
        double faceMatchScore,
        boolean livenessVerified,
        List<String> ocrWarnings,
        String message
) {

    /** Kết quả không đạt ở một bước trước khi so khớp khuôn mặt. */
    public static EkycResultResponse rejected(
            EkycStatus status, EkycResultCode resultCode, String message) {
        return new EkycResultResponse(status, resultCode, false, 0.0, false, List.of(), message);
    }

    /** Kết quả không đạt ở bước so khớp khuôn mặt — người thật nhưng không đúng chủ CCCD. */
    public static EkycResultResponse faceRejected(
            EkycStatus status, double similarity, List<String> ocrWarnings, String message) {
        return new EkycResultResponse(
                status, EkycResultCode.FACE_MISMATCH, false, similarity, true, ocrWarnings, message);
    }

    /** Đạt toàn bộ các bước. */
    public static EkycResultResponse verified(
            double similarity, List<String> ocrWarnings, String message) {
        return new EkycResultResponse(
                EkycStatus.VERIFIED, EkycResultCode.VERIFIED, true, similarity, true,
                ocrWarnings, message);
    }
}
