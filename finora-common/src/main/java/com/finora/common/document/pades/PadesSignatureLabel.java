package com.finora.common.document.pades;

/**
 * Nhãn ghi vào từ điển chữ ký PDF (/Name, /Reason, /Location). Chỉ dùng vai trò và mã bên ký giả danh như trong
 * PHỤ LỤC 02; không đưa họ tên hay số định danh vào đây.
 */
public record PadesSignatureLabel(String name, String reason, String location) {

    public PadesSignatureLabel {
        if (name == null || name.isBlank() || reason == null || reason.isBlank()
                || location == null || location.isBlank()) {
            throw new IllegalArgumentException("Nhãn chữ ký PDF không được để trống");
        }
    }
}
