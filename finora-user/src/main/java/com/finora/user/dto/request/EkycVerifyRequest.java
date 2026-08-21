package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Yêu cầu xác minh eKYC: ảnh hai mặt CCCD.
 * <p>
 * Luồng đã bỏ xác minh khuôn mặt/liveness: bằng chứng định danh là ảnh giấy tờ.
 * Mặt trước được OCR để đối chiếu (hoặc điền) số CCCD của hồ sơ; mặt sau bắt
 * buộc nộp kèm làm bằng chứng người dùng cầm thẻ đầy đủ, phục vụ đối soát tay
 * khi có nghi vấn.
 *
 * @param cccdFrontBase64 ảnh mặt trước CCCD, base64
 * @param cccdBackBase64  ảnh mặt sau CCCD, base64
 */
public record EkycVerifyRequest(

        @NotBlank
        String cccdFrontBase64,

        @NotBlank
        String cccdBackBase64
) {
}
