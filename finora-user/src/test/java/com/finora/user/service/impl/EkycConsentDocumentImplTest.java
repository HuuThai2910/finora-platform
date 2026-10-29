package com.finora.user.service.impl;

import com.finora.user.config.EkycConsentProperties;
import com.finora.user.config.EkycStorageProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kiểm tra nội dung điều khoản eKYC đã điền chỗ trống và hash của nó. */
class EkycConsentDocumentImplTest {

    private static EkycConsentDocumentImpl document(EkycConsentProperties consent, int retentionDays) {
        EkycStorageProperties storage = new EkycStorageProperties();
        storage.setRetentionDays(retentionDays);
        return new EkycConsentDocumentImpl(consent, storage);
    }

    @Test
    void defaultConfigurationFillsEveryPlaceholder() {
        EkycConsentDocumentImpl document = document(new EkycConsentProperties(), 30);

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
        EkycConsentDocumentImpl document = document(new EkycConsentProperties(), 30);

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

        EkycConsentDocumentImpl withGemini = document(new EkycConsentProperties(), 30);
        EkycConsentDocumentImpl noGemini = document(withoutGemini, 30);

        assertThat(noGemini.body()).doesNotContain("Google", "<!--");
        assertThat(noGemini.body()).contains("Vì các máy chủ trên đặt ngoài Việt Nam");
        assertThat(noGemini.textSha256()).isNotEqualTo(withGemini.textSha256());
    }

    @Test
    void contactEmailAndRetentionAreConfigurable() {
        EkycConsentProperties properties = new EkycConsentProperties();
        properties.setContactEmail("privacy@finora.test");
        properties.setStorageLocation("dịch vụ lưu trữ đám mây Amazon S3 đặt tại Singapore");

        EkycConsentDocumentImpl document = document(properties, 45);

        assertThat(document.body())
                .contains("bạn gửi tới privacy@finora.test hoặc mục Hỗ trợ trong ứng dụng FINORA.")
                .contains("**45 ngày**")
                .contains("**dịch vụ lưu trữ đám mây Amazon S3 đặt tại Singapore**");
    }

    @Test
    void lineEndingsAreNormalizedSoWindowsAndLinuxCheckoutsShareTheHash() {
        EkycConsentProperties properties = new EkycConsentProperties();
        String unix = EkycConsentDocumentImpl.render("A {{CONTACT}}\nB {{RETENTION}}\n", properties, 30);
        String windows = EkycConsentDocumentImpl.render("﻿A {{CONTACT}}\r\nB {{RETENTION}}\r\n", properties, 30);

        assertThat(windows).isEqualTo(unix);
        assertThat(EkycConsentDocumentImpl.sha256Hex(windows)).isEqualTo(EkycConsentDocumentImpl.sha256Hex(unix));
    }

    @Test
    void unknownPlaceholderFailsFast() {
        assertThatThrownBy(() -> EkycConsentDocumentImpl.render("Xin chào {{UNKNOWN}}", new EkycConsentProperties(), 30))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingVersionFileFailsAtStartup() {
        EkycConsentProperties properties = new EkycConsentProperties();
        properties.setVersion("ekyc-1999-01");

        assertThatThrownBy(() -> document(properties, 30)).isInstanceOf(IllegalStateException.class);
    }
}
