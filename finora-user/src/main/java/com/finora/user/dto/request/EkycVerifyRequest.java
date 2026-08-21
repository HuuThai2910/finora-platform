package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Yêu cầu xác minh eKYC: ảnh CCCD và chuỗi frame quay theo thử thách của phiên.
 * <p>
 * Giới hạn số frame phải khớp cấu hình phía {@code finora-ai}
 * ({@code EKYC_MIN_FRAMES}, {@code EKYC_MAX_FRAMES}): dưới mức tối thiểu thì
 * không đủ dữ liệu thời gian để kết luận, trên mức tối đa thì request trở thành
 * kênh nhồi ảnh làm nghẽn service.
 *
 * @param sessionId        mã phiên lấy từ {@code liveness-challenge}, dùng một lần
 * @param frames           các frame base64 xếp đúng thứ tự thời gian
 * @param cccdImageBase64  ảnh mặt trước CCCD để OCR và so khớp khuôn mặt
 */
public record EkycVerifyRequest(

        @NotBlank
        String sessionId,

        @NotEmpty
        @Size(min = EkycVerifyRequest.MIN_FRAMES, max = EkycVerifyRequest.MAX_FRAMES,
                message = "Số frame phải từ 3 đến 20")
        List<@NotBlank String> frames,

        @NotBlank
        String cccdImageBase64
) {

    public static final int MIN_FRAMES = 3;
    public static final int MAX_FRAMES = 20;
}
