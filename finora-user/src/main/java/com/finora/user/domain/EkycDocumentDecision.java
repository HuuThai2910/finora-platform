package com.finora.user.domain;

/**
 * Quyết định của finora-ai về ảnh hai mặt thẻ khi đã đọc được dữ liệu.
 * <p>
 * {@code RETAKE} của AI không bao giờ thành một lượt xác minh nên không có ở đây.
 * {@code AUTO_OK} chỉ là điều kiện cần để tự động {@code VERIFIED}: còn phải qua liveness {@code PASS}.
 */
public enum EkycDocumentDecision {
    /** QR và MRZ (hoặc nguồn tất định + nguồn đọc độc lập) khớp nhau, chất lượng đạt. */
    AUTO_OK,
    /** Đọc được nhưng chưa đủ tin cậy (chỉ AI, ảnh thư viện, thẻ mã vạch, cờ mềm…) — cần người duyệt. */
    NEEDS_REVIEW;

    /** Giá trị lạ từ AI được coi là cần duyệt — không bao giờ tự nâng thành AUTO_OK. */
    public static EkycDocumentDecision fromAi(String raw) {
        return "AUTO_OK".equals(raw) ? AUTO_OK : NEEDS_REVIEW;
    }
}
