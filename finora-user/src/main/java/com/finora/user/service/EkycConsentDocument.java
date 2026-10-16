package com.finora.user.service;

import com.finora.user.config.EkycConsentProperties;
import com.finora.user.config.EkycStorageProperties;
import com.finora.user.support.Sha256;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nội dung điều khoản eKYC hiện hành, đã điền chỗ trống, kèm SHA-256 — dựng một lần lúc khởi động.
 * <p>
 * File {@code ekyc/consent-<version>.md} chứa đúng Phụ lục A của đề xuất eKYC với các chỗ trống
 * {@code {{CONTACT}}}, {@code {{RETENTION}}}, {@code {{STORAGE_LOCATION}}}, {@code {{SERVER_LOCATION}}},
 * {@code {{EFFECTIVE_DATE}}} và đoạn Gemini bọc trong {@code <!-- IF:GEMINI --> … <!-- ENDIF:GEMINI -->}.
 * <p>
 * Hash được tính trên đúng chuỗi trả cho client: xuống dòng chuẩn hoá về {@code \n} và bỏ BOM, nên file
 * checkout bằng CRLF (Windows) hay LF (Linux) cho cùng một hash và đồng ý ghi ở máy này vẫn khớp ở máy kia.
 */
@Component
public class EkycConsentDocument {

    public static final String TITLE = "Điều khoản xử lý dữ liệu khi xác minh danh tính (eKYC)";

    public static final String CONSENT_LABEL =
            "Tôi đã đọc và đồng ý với Điều khoản xử lý dữ liệu khi xác minh danh tính.";

    /** Bốn mục đích ghi vào bản ghi đồng ý; nội dung từng mục nằm trong thân điều khoản. */
    public static final List<String> PURPOSES =
            List.of("ID_DOCUMENT", "FACE_BIOMETRIC", "STORAGE_ABROAD", "OWN_DOCUMENT");

    /** Câu thay cho email liên hệ khi chưa cấu hình email. */
    static final String SUPPORT_CHANNEL = "mục Hỗ trợ trong ứng dụng FINORA";

    private static final Pattern GEMINI_BLOCK =
            Pattern.compile("<!-- IF:GEMINI -->\\n?(.*?)<!-- ENDIF:GEMINI -->\\n?", Pattern.DOTALL);

    private final String version;
    private final String body;
    private final String textSha256;

    public EkycConsentDocument(EkycConsentProperties consentProperties, EkycStorageProperties storageProperties) {
        this.version = consentProperties.getVersion();
        this.body = render(loadTemplate(version), consentProperties, storageProperties.getRetentionDays());
        this.textSha256 = sha256Hex(body);
    }

    public String version() {
        return version;
    }

    public String body() {
        return body;
    }

    public String textSha256() {
        return textSha256;
    }

    /** Điền chỗ trống và bật/tắt đoạn Gemini; chỗ trống còn sót là lỗi cấu hình nên dừng ngay. */
    static String render(String template, EkycConsentProperties properties, int retentionDays) {
        String normalized = normalize(template);
        Matcher matcher = GEMINI_BLOCK.matcher(normalized);
        StringBuilder withBlocks = new StringBuilder();
        while (matcher.find()) {
            String replacement = properties.isMentionsGemini() ? matcher.group(1) : "";
            matcher.appendReplacement(withBlocks, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(withBlocks);

        String contactEmail = properties.getContactEmail() == null ? "" : properties.getContactEmail().trim();
        String contact = contactEmail.isEmpty() ? SUPPORT_CHANNEL : contactEmail + " hoặc " + SUPPORT_CHANNEL;

        String rendered = withBlocks.toString()
                .replace("{{CONTACT}}", contact)
                .replace("{{RETENTION}}", retentionDays + " ngày")
                .replace("{{STORAGE_LOCATION}}", properties.getStorageLocation())
                .replace("{{SERVER_LOCATION}}", properties.getServerLocation())
                .replace("{{EFFECTIVE_DATE}}", properties.getEffectiveDate())
                .strip();
        if (rendered.contains("{{") || rendered.contains("<!--")) {
            throw new IllegalStateException("Điều khoản eKYC còn chỗ trống hoặc đánh dấu chưa xử lý");
        }
        return rendered;
    }

    static String sha256Hex(String text) {
        return Sha256.hex(text);
    }

    private static String normalize(String raw) {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String loadTemplate(String version) {
        ClassPathResource resource = new ClassPathResource("ekyc/consent-" + version + ".md");
        if (!resource.exists()) {
            throw new IllegalStateException("Thiếu file điều khoản eKYC cho phiên bản " + version);
        }
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file điều khoản eKYC", e);
        }
    }
}
