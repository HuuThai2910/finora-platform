package com.finora.user.service;

import java.util.List;

/**
 * Nội dung điều khoản eKYC hiện hành, đã điền chỗ trống, kèm SHA-256 — dựng một lần lúc khởi động.
 */
public interface EkycConsentDocument {

    String TITLE = "Điều khoản xử lý dữ liệu khi xác minh danh tính (eKYC)";

    String CONSENT_LABEL =
            "Tôi đã đọc và đồng ý với Điều khoản xử lý dữ liệu khi xác minh danh tính.";

    /**
     * Mục đích ghi vào bản ghi đồng ý; nội dung từng mục nằm trong thân điều khoản. Từ {@code ekyc-2026-11} không còn
     * {@code FACE_BIOMETRIC} (eKYC chỉ đọc CCCD, không quay video khuôn mặt).
     */
    List<String> PURPOSES = List.of("ID_DOCUMENT", "STORAGE_ABROAD", "OWN_DOCUMENT");

    String version();

    String body();

    String textSha256();
}
