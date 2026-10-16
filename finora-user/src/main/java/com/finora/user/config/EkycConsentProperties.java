package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình điều khoản eKYC ({@code finora.ekyc.consent.*}).
 * <p>
 * Giá trị ở đây được điền vào chỗ trống của file {@code ekyc/consent-<version>.md}. Đổi bất kỳ giá trị
 * nào làm đổi nội dung hiển thị và SHA-256, nên mọi đồng ý cũ tự hết hiệu lực và người dùng phải đồng ý
 * lại — đây là hành vi mong muốn, không phải lỗi. Khi đổi chữ trong file, phải tăng {@link #version}.
 */
@ConfigurationProperties(prefix = "finora.ekyc.consent")
@Getter
@Setter
public class EkycConsentProperties {

    /** Phiên bản điều khoản, cũng là hậu tố tên file nội dung. */
    private String version = "ekyc-2026-10";

    /** Email nhận yêu cầu về dữ liệu cá nhân; để trống thì điều khoản chỉ dẫn tới mục Hỗ trợ trong app. */
    private String contactEmail = "";

    /** Nơi lưu ảnh/video, ví dụ "dịch vụ lưu trữ đám mây Amazon S3 đặt tại Singapore". */
    private String storageLocation = "hệ thống lưu trữ thử nghiệm của FINORA";

    /** Quốc gia đặt máy chủ xử lý. */
    private String serverLocation = "Malaysia";

    /** Ngày áp dụng hiển thị trên điều khoản ({@code dd/mm/yyyy}). */
    private String effectiveDate = "16/10/2026";

    /** Còn gọi Gemini để điền chỗ trống thì phải hiển thị đoạn về chuyển phần chữ trên thẻ cho Google. */
    private boolean mentionsGemini = true;
}
