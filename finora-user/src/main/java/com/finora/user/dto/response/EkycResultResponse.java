package com.finora.user.dto.response;

import com.finora.user.domain.EkycResultCode;
import com.finora.user.domain.EkycStatus;

import java.util.List;

/**
 * Kết quả một lần gọi eKYC (quét hoặc xác nhận).
 *
 * @param status      trạng thái hồ sơ sau lần gọi này
 * @param resultCode  lần gọi dừng ở bước nào — client dựa vào đây để hướng dẫn
 * @param ocrWarnings các trường mềm lệch so với hồ sơ (họ tên, ngày sinh) — không chặn
 * @param message     thông điệp hiển thị cho người dùng
 * @param draft       thông tin OCR đọc được, chờ người dùng soát — chỉ có khi
 *                    {@code resultCode == DRAFT_READY}; hồ sơ chưa lưu gì ở bước này
 */
public record EkycResultResponse(
        EkycStatus status,
        EkycResultCode resultCode,
        List<String> ocrWarnings,
        String message,
        EkycDraft draft
) {

    /**
     * Bản nháp thông tin đọc từ CCCD — hiển thị nguyên văn cho người dùng soát.
     * Ngày sinh giữ dạng chuỗi như in trên thẻ (dd/mm/yyyy).
     */
    public record EkycDraft(
            String idNumber,
            String fullName,
            String dateOfBirth,
            String gender,
            String placeOfOrigin,
            String address
    ) {
    }

    /** Kết quả không đạt — hồ sơ giữ nguyên trạng thái hiện tại. */
    public static EkycResultResponse rejected(
            EkycStatus status, EkycResultCode resultCode, String message) {
        return new EkycResultResponse(status, resultCode, List.of(), message, null);
    }

    /** OCR xong, chờ người dùng soát và xác nhận — chưa lưu gì vào hồ sơ. */
    public static EkycResultResponse draftReady(
            EkycStatus status, EkycDraft draft, List<String> ocrWarnings, String message) {
        return new EkycResultResponse(status, EkycResultCode.DRAFT_READY, ocrWarnings, message, draft);
    }

    /** Người dùng đã xác nhận, hồ sơ được lưu và chuyển VERIFIED. */
    public static EkycResultResponse verified(List<String> ocrWarnings, String message) {
        return new EkycResultResponse(
                EkycStatus.VERIFIED, EkycResultCode.VERIFIED, ocrWarnings, message, null);
    }
}
