package com.finora.user.service;

/**
 * Worker quét dọn bằng chứng eKYC (mặc định mỗi giờ, tắt bằng {@code EKYC_SWEEPER_ENABLED=false}).
 * <ol>
 *   <li>Lượt {@code IN_PROGRESS} quá 24 giờ → {@code ABANDONED} (người dùng bỏ dở).</li>
 *   <li>Object của lượt {@code ABANDONED} còn trên kho → xoá (xoá đồng bộ lần trước có thể đã lỗi).</li>
 *   <li>Object {@code final} quá {@code EKYC_EVIDENCE_RETENTION_DAYS} ngày → xoá; bản nháp mã hoá của
 *       lượt đã duyệt/từ chối quá hạn → xoá.</li>
 *   <li>Object tạo trước một lần rút đồng ý của chính chủ → xoá (lần rút đó xoá theo tiền tố bị lỗi).</li>
 * </ol>
 * Không dựa vào vòng đời của bucket (chỉ là lớp phòng thủ thêm, SeaweedFS chưa chắc thực thi lọc theo tag).
 * Mỗi instance chỉ xoá object trong tiền tố {@code ekyc/<env>/} của mình vì nhiều môi trường có thể dùng
 * chung database. Object xoá lỗi giữ {@code deleted_at = null} để vòng sau thử lại; mỗi vòng giới hạn số
 * lô để không chiếm worker quá lâu.
 */
public interface EkycEvidenceSweeper {

    void run();

    SweepReport sweep();

    record SweepReport(int abandonedAttempts, int deletedObjects, int clearedDrafts) {
    }
}
