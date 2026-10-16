package com.finora.user.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Bước quét thẻ eKYC (EKYC-002 mục 3.2): ảnh hai mặt thẻ và mã QR mobile đã đọc được nếu có.
 * <p>
 * Server tự kiểm đồng ý điều khoản trong database; {@code consentVersion} chỉ để đối chiếu nhật ký,
 * không được tin như bằng chứng đồng ý. {@code frontSource}/{@code backSource} do client tự khai nên
 * chỉ ảnh hưởng quyết định theo hướng chặt hơn (ảnh thư viện tối đa là chờ duyệt).
 *
 * @param cccdFrontBase64 ảnh mặt trước JPEG/PNG, base64 (cho phép tiền tố {@code data:image/...;base64,})
 * @param cccdBackBase64  ảnh mặt sau JPEG/PNG, base64
 * @param frontSource     {@code IN_APP_CAMERA} (mặc định) | {@code SYSTEM_CAMERA} | {@code GALLERY}
 * @param backSource      như trên cho mặt sau
 * @param qr              mã QR mobile quét được khi ngắm, có thể {@code null}
 * @param consentVersion  phiên bản điều khoản client đang hiển thị
 */
public record EkycVerifyRequest(

        @NotBlank
        @Size(max = 6_000_000)
        String cccdFrontBase64,

        @NotBlank
        @Size(max = 6_000_000)
        String cccdBackBase64,

        @Pattern(regexp = "IN_APP_CAMERA|SYSTEM_CAMERA|GALLERY")
        String frontSource,

        @Pattern(regexp = "IN_APP_CAMERA|SYSTEM_CAMERA|GALLERY")
        String backSource,

        @Valid
        QrPayload qr,

        @Size(max = 40)
        String consentVersion
) {

    public static final String SOURCE_IN_APP_CAMERA = "IN_APP_CAMERA";

    /** Constructor rút gọn cho hai ảnh, các trường khác theo mặc định. */
    public EkycVerifyRequest(String cccdFrontBase64, String cccdBackBase64) {
        this(cccdFrontBase64, cccdBackBase64, null, null, null, null);
    }

    public String frontSourceOrDefault() {
        return frontSource == null || frontSource.isBlank() ? SOURCE_IN_APP_CAMERA : frontSource;
    }

    public String backSourceOrDefault() {
        return backSource == null || backSource.isBlank() ? SOURCE_IN_APP_CAMERA : backSource;
    }

    /**
     * @param text   chuỗi QR thô ({@code ID12|CMND9|Họ tên|ddmmyyyy|Giới tính|Địa chỉ|ddmmyyyy})
     * @param side   mặt thẻ chứa QR
     * @param method cách đọc: quét sống, quét trên ảnh tĩnh hay trình quét hệ thống — chỉ để nhật ký
     */
    public record QrPayload(
            @Size(max = 2000)
            String text,

            @Pattern(regexp = "FRONT|BACK")
            String side,

            @Pattern(regexp = "LIVE_SCAN|STILL_SCAN|SCANNER")
            String method
    ) {
    }
}
