package com.finora.user.integration.storage;

import com.finora.user.domain.EkycEvidenceStage;

/**
 * Kho lưu bị tắt ({@code EKYC_EVIDENCE_ENABLED=false}, ví dụ bản Azure chưa có bucket S3).
 * <p>
 * Ghi/đọc luôn lỗi để luồng eKYC dừng ở {@code STORAGE_UNAVAILABLE}; xoá là no-op vì kho này chưa
 * từng lưu gì — nhờ vậy rút đồng ý vẫn hoàn tất được khi kho tắt.
 */
public class DisabledEkycEvidenceStorage implements EkycEvidenceStorage {

    private static final String DISABLED = "Kho lưu bằng chứng eKYC đang tắt (EKYC_EVIDENCE_ENABLED=false)";

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void put(String key, byte[] content, String contentType, EkycEvidenceStage stage) {
        throw new EkycStorageException(DISABLED);
    }

    @Override
    public StoredObject open(String key) {
        throw new EkycStorageException(DISABLED);
    }

    @Override
    public boolean setStage(String key, EkycEvidenceStage stage) {
        return false;
    }

    @Override
    public void delete(String key) {
        // Kho tắt chưa từng ghi object nào nên không có gì để xoá.
    }

    @Override
    public int deletePrefix(String prefix) {
        return 0;
    }
}
