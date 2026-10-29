package com.finora.user.service;

import com.finora.user.config.EkycConsentProperties;
import com.finora.user.config.EkycStorageProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kiểm tra nội dung điều khoản eKYC đã điền chỗ trống và hash của nó. */
class EkycConsentDocumentTest {

    private static EkycConsentDocument document(EkycConsentProperties consent, int retentionDays) {
        EkycStorageProperties storage = new EkycStorageProperties();
        storage.setRetentionDays(retentionDays);
        return new EkycConsentDocument(consent, storage);
    }

    @Test
    void defaultConfigurationFillsEveryPlaceholder() {
        EkycConsentDocument document = document(new EkycConsentProperties(), 30);

        assertThat(document.version()).isEqualTo("ekyc-2026-10");
        assertThat(document.body())
                .startsWith("### Điều khoản xử lý dữ liệu khi xác minh danh tính (eKYC)")
                .contains("Phiên bản ekyc-2026-10 · Áp dụng từ 16/10/2026")
                .contains("bạn gửi tới mục Hỗ trợ trong ứng dụng FINORA.")
                .contains("Máy chủ xử lý của FINORA đặt tại **Malaysia**.")
                .contains("có mã hoá, trên **hệ thống lưu trữ thử nghiệm của FINORA**.")
                .contains("được lưu trong **30 ngày**, sau đó tự động xoá.")
                .contains("dịch vụ AI của Google")
                .doesNotContain("{{", "}}", "<!--", "[email liên hệ]", "[nơi lưu ảnh]", "Bắt đầu xác minh ]",
                        "☐", "(chỉ khi dùng Gemini)");
        assertThat(document.body()).doesNotEndWith("---");
    }

    @Test
    void hashIsSha256OfTheExactBody() throws Exception {
        EkycConsentDocument document = document(new EkycConsentProperties(), 30);

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(document.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(document.textSha256()).isEqualTo(expected).hasSize(64);
        assertThat(document(new EkycConsentProperties(), 30).textSha256())
                .as("cùng cấu hình phải cho cùng hash").isEqualTo(expected);
    }

    @Test
    void disablingGeminiRemovesItsParagraphAndChangesHash() {
        EkycConsentProperties withoutGemini = new EkycConsentProperties();
        withoutGemini.setMentionsGemini(false);

        EkycConsentDocument withGemini = document(new EkycConsentProperties(), 30);
        EkycConsentDocument noGemini = document(withoutGemini, 30);

        assertThat(noGemini.body()).doesNotContain("Google", "<!--");
        assertThat(noGemini.body()).contains("Vì các máy chủ trên đặt ngoài Việt Nam");
        assertThat(noGemini.textSha256()).isNotEqualTo(withGemini.textSha256());
    }

    @Test
    void contactEmailAndRetentionAreConfigurable() {
        EkycConsentProperties properties = new EkycConsentProperties();
        properties.setContactEmail("privacy@finora.test");
        properties.setStorageLocation("dịch vụ lưu trữ đám mây Amazon S3 đặt tại Singapore");

        EkycConsentDocument document = document(properties, 45);

        assertThat(document.body())
                .contains("bạn gửi tới privacy@finora.test hoặc mục Hỗ trợ trong ứng dụng FINORA.")
                .contains("**45 ngày**")
                .contains("**dịch vụ lưu trữ đám mây Amazon S3 đặt tại Singapore**");
    }

    @Test
    void lineEndingsAreNormalizedSoWindowsAndLinuxCheckoutsShareTheHash() {
        EkycConsentProperties properties = new EkycConsentProperties();
        String unix = EkycConsentDocument.render("A {{CONTACT}}\nB {{RETENTION}}\n", properties, 30);
        String windows = EkycConsentDocument.render("﻿A {{CONTACT}}\r\nB {{RETENTION}}\r\n", properties, 30);

        assertThat(windows).isEqualTo(unix);
        assertThat(EkycConsentDocument.sha256Hex(windows)).isEqualTo(EkycConsentDocument.sha256Hex(unix));
    }

    @Test
    void unknownPlaceholderFailsFast() {
        assertThatThrownBy(() -> EkycConsentDocument.render("Xin chào {{UNKNOWN}}", new EkycConsentProperties(), 30))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingVersionFileFailsAtStartup() {
        EkycConsentProperties properties = new EkycConsentProperties();
        properties.setVersion("ekyc-1999-01");

        assertThatThrownBy(() -> document(properties, 30)).isInstanceOf(IllegalStateException.class);
    }
}
