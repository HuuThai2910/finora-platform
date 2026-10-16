package com.finora.user.support;

import com.finora.common.enums.user.EkycResultCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thông điệp tiếng Việt cho mã kết quả, lý do chụp lại thẻ và lý do liveness (EKYC-002 mục 3.6).
 * <p>
 * Backend là nguồn thông điệp chính để mọi client nói cùng một câu; mobile chỉ giữ bản dự phòng.
 * Đổi câu chữ ở đây không đổi hợp đồng vì client phân nhánh theo mã, không theo thông điệp.
 */
public final class EkycMessages {

    private static final Map<EkycResultCode, String> RESULT_MESSAGES = new EnumMap<>(EkycResultCode.class);

    private static final Map<String, String> RETAKE_MESSAGES = Map.ofEntries(
            Map.entry("BLUR", "Ảnh bị mờ. Đưa điện thoại ra xa khoảng 20 cm, giữ yên 1 giây rồi chụp lại."),
            Map.entry("GLARE", "Ảnh bị loá. Nghiêng thẻ một chút hoặc tránh đèn chiếu thẳng."),
            Map.entry("CARD_CUT", "Chưa thấy đủ 4 góc thẻ. Đặt thẻ gọn trong khung."),
            Map.entry("WRONG_SIDE", "Có vẻ bạn chụp nhầm mặt thẻ."),
            Map.entry("MRZ_UNREADABLE", "Chưa đọc được 3 dòng ký tự ở cuối mặt sau. Chụp đủ sáng, rõ nét."),
            Map.entry("QR_UNREADABLE", "Chưa đọc được mã QR. Đưa mã QR vào giữa khung và giữ yên."),
            Map.entry("PORTRAIT_UNUSABLE", "Ảnh chân dung trên thẻ bị mờ hoặc loá, chưa dùng được để so khuôn mặt. "
                    + "Hãy chụp lại mặt trước."),
            Map.entry("TOO_DARK", "Ảnh quá tối. Ra chỗ sáng hơn."),
            Map.entry("SOURCES_DISAGREE", "Thông tin đọc được ở hai mặt thẻ chưa khớp. Hãy chụp lại cả hai mặt thật rõ."),
            Map.entry("NO_DATA", "Chưa đọc được thông tin trên thẻ. Hãy chụp lại rõ hơn."));

    private static final String ACTIONS_MESSAGE = "Chưa làm đúng các động tác theo hướng dẫn.";
    private static final String VIDEO_MESSAGE = "Video không đọc được hoặc quá ngắn, vui lòng quay lại.";

    private static final Map<String, String> LIVENESS_REASON_MESSAGES = Map.ofEntries(
            Map.entry("TOO_DARK", "Ảnh quá tối, hãy ra chỗ sáng hơn."),
            Map.entry("NO_FACE", "Không thấy khuôn mặt. Giữ mặt trong khung oval."),
            Map.entry("FACE_TOO_SMALL", "Mặt ở quá xa. Giữ điện thoại cách mặt khoảng một cánh tay."),
            Map.entry("BLURRY", "Video bị mờ. Giữ máy yên và quay đầu chậm hơn."),
            Map.entry("VIDEO_UNREADABLE", VIDEO_MESSAGE),
            Map.entry("VIDEO_TOO_SHORT", VIDEO_MESSAGE),
            Map.entry("MULTIPLE_FACES", "Chỉ một người được xuất hiện trong khung hình."),
            Map.entry("ACTIONS_MISMATCH", ACTIONS_MESSAGE),
            Map.entry("ACTIONS_MISSING", ACTIONS_MESSAGE),
            Map.entry("ACTIONS_EXTRA", ACTIONS_MESSAGE),
            Map.entry("INTERVALS_MISMATCH", "Động tác chưa khớp nhịp hướng dẫn trên màn hình."),
            Map.entry("IDENTITY_INCONSISTENT", "Khuôn mặt trong video không nhất quán."),
            Map.entry("FACE_MISMATCH", "Khuôn mặt chưa khớp với ảnh trên thẻ."),
            Map.entry("FACE_MATCH_GRAY", "Hệ thống chưa chắc chắn khuôn mặt khớp ảnh trên thẻ, hồ sơ sẽ được duyệt thêm."),
            Map.entry("MEDIA_REPLAYED", "Video này đã được dùng trước đó."));

    static {
        RESULT_MESSAGES.put(EkycResultCode.DRAFT_READY,
                "Đã đọc thông tin trên thẻ. Tiếp tục bước kiểm tra khuôn mặt.");
        RESULT_MESSAGES.put(EkycResultCode.DRAFT_EXPIRED, "Phiên xác minh đã hết hạn, vui lòng quét lại thẻ.");
        RESULT_MESSAGES.put(EkycResultCode.VERIFIED, "Xác minh danh tính thành công.");
        RESULT_MESSAGES.put(EkycResultCode.OCR_FAILED, "Không đọc được ảnh thẻ, vui lòng chụp lại rõ hơn.");
        RESULT_MESSAGES.put(EkycResultCode.ID_MISMATCH, "Số định danh trên thẻ không khớp thông tin đã có trong hồ sơ.");
        RESULT_MESSAGES.put(EkycResultCode.ID_TAKEN, "Số định danh này đã được đăng ký cho một tài khoản khác.");
        RESULT_MESSAGES.put(EkycResultCode.RATE_LIMITED, "Bạn thao tác quá nhanh, vui lòng thử lại sau ít giây.");
        RESULT_MESSAGES.put(EkycResultCode.AI_UNAVAILABLE, "Dịch vụ xác minh đang bận, vui lòng thử lại sau ít phút.");
        RESULT_MESSAGES.put(EkycResultCode.RETAKE, "Vui lòng chụp lại theo hướng dẫn.");
        RESULT_MESSAGES.put(EkycResultCode.CARD_EXPIRED,
                "Thẻ căn cước đã hết hạn. Vui lòng dùng thẻ còn hiệu lực.");
        RESULT_MESSAGES.put(EkycResultCode.CARD_UNSUPPORTED,
                "FINORA chỉ nhận thẻ Căn cước công dân gắn chip hoặc thẻ Căn cước.");
        RESULT_MESSAGES.put(EkycResultCode.UNDERAGE, "Bạn cần đủ 18 tuổi để sử dụng dịch vụ vay và đầu tư.");
        RESULT_MESSAGES.put(EkycResultCode.CONSENT_REQUIRED,
                "Vui lòng đọc và đồng ý điều khoản trước khi xác minh.");
        RESULT_MESSAGES.put(EkycResultCode.STORAGE_UNAVAILABLE, "Hệ thống lưu trữ đang bận, vui lòng thử lại sau.");
        RESULT_MESSAGES.put(EkycResultCode.REVIEW_PENDING,
                "Hồ sơ của bạn đang được duyệt. FINORA sẽ thông báo khi có kết quả.");
        RESULT_MESSAGES.put(EkycResultCode.LIVENESS_REQUIRED,
                "Bạn cần hoàn tất bước kiểm tra khuôn mặt trước khi xác nhận.");
        RESULT_MESSAGES.put(EkycResultCode.CHALLENGE_EXPIRED, "Phiên quay đã hết hạn, vui lòng thử lại.");
        RESULT_MESSAGES.put(EkycResultCode.LIVENESS_PASSED, "Kiểm tra khuôn mặt thành công.");
        RESULT_MESSAGES.put(EkycResultCode.LIVENESS_REVIEW,
                "Đã ghi nhận. Hồ sơ sẽ được duyệt thêm sau khi bạn xác nhận.");
        RESULT_MESSAGES.put(EkycResultCode.LIVENESS_FAILED,
                "Chưa làm đúng các động tác. Hãy làm chậm và rõ từng động tác.");
        RESULT_MESSAGES.put(EkycResultCode.FACE_MISMATCH, "Khuôn mặt chưa khớp với ảnh trên thẻ căn cước.");
    }

    /** Thông điệp khi hồ sơ đã xác minh từ trước mà client gọi lại. */
    public static final String ALREADY_VERIFIED = "Hồ sơ đã được xác minh trước đó.";

    /** Video cần quay lại nhưng AI không nêu lý do có thông điệp. */
    public static final String LIVENESS_RETAKE = "Vui lòng quay lại video theo hướng dẫn trên màn hình.";

    private EkycMessages() {
    }

    public static String result(EkycResultCode code) {
        return RESULT_MESSAGES.getOrDefault(code, "Không xác minh được, vui lòng thử lại.");
    }

    /** Thông điệp chụp lại thẻ theo lý do; lý do lạ dùng câu chung. */
    public static String retake(String reason) {
        return RETAKE_MESSAGES.getOrDefault(reason, "Vui lòng chụp lại ảnh thẻ rõ nét, đủ sáng.");
    }

    /** Thông điệp cho mã lý do liveness; mã tích cực hoặc mã kỹ thuật không có thông điệp trả rỗng. */
    public static Optional<String> livenessReason(String code) {
        return Optional.ofNullable(LIVENESS_REASON_MESSAGES.get(code));
    }
}
