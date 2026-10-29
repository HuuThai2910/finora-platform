package com.finora.user.dto.response;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;

import java.util.List;

/**
 * Kết quả một lần gọi eKYC — dùng chung cho quét thẻ, kiểm tra khuôn mặt và xác nhận (EKYC-002 mục 3.3).
 * <p>
 * Thứ tự và tên field là hợp đồng với mobile/web; field không áp dụng vẫn có mặt với {@code null}
 * (hoặc danh sách rỗng) để client đọc một khuôn duy nhất.
 *
 * @param status         trạng thái hồ sơ sau lần gọi
 * @param resultCode     lần gọi dừng ở bước nào — client dựa vào đây để hướng dẫn
 * @param message        thông điệp tiếng Việt cho người dùng; backend là nguồn chính
 * @param ocrWarnings    cảnh báo mềm (lệch với hồ sơ đã khai, mã tỉnh lạ…) — không chặn
 * @param cardType       đời thẻ AI nhận diện, {@code null} nếu chưa đọc
 * @param reviewRequired hồ sơ sẽ phải qua nhân viên duyệt sau khi xác nhận
 * @param retake         mặt thẻ cần chụp lại kèm lý do và thông điệp
 * @param draft          thông tin đọc được kèm nguồn, chờ người dùng soát; hồ sơ chưa lưu gì
 * @param nextStep       bước client làm tiếp
 * @param attemptsLeft   số lần còn được thử liveness trước khi hồ sơ chuyển duyệt tay
 * @param liveness       kết quả kiểm tra khuôn mặt của lần gọi này
 */
public record EkycResultResponse(
        EkycStatus status,
        EkycResultCode resultCode,
        String message,
        List<String> ocrWarnings,
        String cardType,
        boolean reviewRequired,
        List<RetakeItem> retake,
        DraftView draft,
        EkycNextStep nextStep,
        Integer attemptsLeft,
        LivenessView liveness
) {

    public EkycResultResponse {
        ocrWarnings = ocrWarnings == null ? List.of() : List.copyOf(ocrWarnings);
        retake = retake == null ? List.of() : List.copyOf(retake);
    }

    /** Mặt thẻ cần chụp lại: {@code side} FRONT/BACK, {@code reason} theo hợp đồng AI. */
    public record RetakeItem(String side, String reason, String message) {
    }

    /**
     * Bản nháp theo thứ tự hợp đồng. Trường không thu thập (ví dụ quê quán khi tắt trường tuỳ chọn)
     * là {@code null} thay vì object rỗng.
     */
    public record DraftView(
            DraftFieldView idNumber,
            DraftFieldView fullName,
            DraftFieldView dateOfBirth,
            DraftFieldView gender,
            DraftFieldView address,
            DraftFieldView placeOfOrigin,
            DraftFieldView birthRegistrationPlace,
            DraftFieldView issueDate,
            DraftFieldView expiryDate
    ) {
    }

    /**
     * @param sources  {@code QR}, {@code MRZ}, {@code OCR}, {@code AI} hoặc {@code USER} (người dùng đã sửa)
     * @param editable chỉ {@code true} khi nguồn đúng bằng {@code ["AI"]} và là trường văn bản tự do
     */
    public record DraftFieldView(String value, List<String> sources, boolean editable) {
    }

    /**
     * @param result         {@code PASSED | REVIEW | FAILED | RETAKE}
     * @param faceMatchScore điểm so khuôn mặt video với ảnh chân dung trên thẻ, {@code null} nếu không đo được
     * @param reasons        mã lý do AI kèm thông điệp hướng dẫn
     */
    public record LivenessView(String result, Double faceMatchScore, List<LivenessReason> reasons) {
        public LivenessView {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record LivenessReason(String code, String message) {
    }
}
