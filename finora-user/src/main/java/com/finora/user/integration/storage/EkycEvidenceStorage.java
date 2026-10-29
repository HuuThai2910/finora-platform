package com.finora.user.integration.storage;

import com.finora.user.domain.EkycEvidenceStage;

/**
 * Kho lưu bằng chứng eKYC (ảnh hai mặt thẻ, video, khung hình, {@code result.json}) qua S3 API.
 * <p>
 * Ranh giới tích hợp có hai bản cài đặt: {@link S3EkycEvidenceStorage} (SeaweedFS local hoặc Amazon S3)
 * và {@link DisabledEkycEvidenceStorage} khi {@code EKYC_EVIDENCE_ENABLED=false}. Lỗi ghi/đọc/xoá ném
 * {@link EkycStorageException}; gắn tag giai đoạn là best-effort vì cột {@code ekyc_evidence.stage}
 * và worker quét dọn mới là nguồn chuẩn cho hạn lưu.
 * <p>
 * Không gọi trong lúc giữ transaction database: mỗi thao tác là một lời gọi mạng.
 */
public interface EkycEvidenceStorage {

    /** {@code false} khi kho bị tắt bằng cấu hình — mọi bước cần lưu bằng chứng phải dừng. */
    boolean isEnabled();

    /** Ghi object kèm tag {@code stage=<giai đoạn>}; chỉ ném lỗi khi chính object không ghi được. */
    void put(String key, byte[] content, String contentType, EkycEvidenceStage stage);

    /** Mở luồng đọc object; người gọi phải đóng {@link StoredObject}. */
    StoredObject open(String key);

    /** Đổi tag giai đoạn (PutObjectTagging). Không ném lỗi: trả {@code false} nếu kho không nhận tag. */
    boolean setStage(String key, EkycEvidenceStage stage);

    /** Xoá một object; object không tồn tại được coi là đã xoá. */
    void delete(String key);

    /** Xoá mọi object dưới tiền tố; trả số object đã xoá. */
    int deletePrefix(String prefix);
}
