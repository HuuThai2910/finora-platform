package com.finora.user.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;

/**
 * Giai đoạn lưu của một object bằng chứng; giá trị chữ thường trùng tag {@code stage=…} trên object.
 * <p>
 * Cột {@code ekyc_evidence.stage} là nguồn chuẩn cho việc quét dọn: tag trên object chỉ phục vụ
 * vòng đời của bucket (lớp phòng thủ thêm) và được gắn best-effort.
 */
public enum EkycEvidenceStage {
    /** Lượt đang làm dở; bị dọn nếu không có kết quả cuối. */
    PENDING("pending"),
    /** Lượt đã có kết quả cuối; giữ theo hạn lưu cấu hình. */
    FINAL("final");

    private final String value;

    EkycEvidenceStage(String value) {
        this.value = value;
    }

    /** Giá trị ghi DB và tag object. */
    public String value() {
        return value;
    }

    public static EkycEvidenceStage fromValue(String value) {
        return Arrays.stream(values())
                .filter(stage -> stage.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Giai đoạn bằng chứng không hợp lệ: " + value));
    }

    /** Ghi DB đúng chữ thường như ràng buộc {@code ck_ekyc_evidence_stage}. */
    @Converter(autoApply = false)
    public static class DbConverter implements AttributeConverter<EkycEvidenceStage, String> {
        @Override
        public String convertToDatabaseColumn(EkycEvidenceStage stage) {
            return stage == null ? null : stage.value;
        }

        @Override
        public EkycEvidenceStage convertToEntityAttribute(String value) {
            return value == null ? null : fromValue(value);
        }
    }
}
