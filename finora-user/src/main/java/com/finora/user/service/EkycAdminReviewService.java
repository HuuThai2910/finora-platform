package com.finora.user.service;

import com.finora.common.dto.PageResponse;
import com.finora.user.dto.response.EkycAttemptDetailResponse;
import com.finora.user.dto.response.EkycAttemptSummaryResponse;
import com.finora.user.integration.storage.StoredObject;

import java.util.UUID;

/**
 * Trang Duyệt eKYC của admin (EKYC-002 mục 3.7): danh sách lượt, chi tiết, xem bằng chứng, duyệt/từ chối.
 */
public interface EkycAdminReviewService {

    /**
     * @param status trạng thái lượt; bỏ trống = {@code MANUAL_REVIEW} (hàng chờ duyệt), {@code ALL} = mọi trạng thái
     * @param size   kích thước trang, chặn trong 1..100
     */
    PageResponse<EkycAttemptSummaryResponse> list(String status, int page, int size);

    /** Chi tiết lượt, có bản nháp đã giải mã; ghi nhật ký truy cập (không gắn file cụ thể). */
    EkycAttemptDetailResponse detail(String attemptId, UUID adminKeycloakId);

    /**
     * Mở luồng đọc một file bằng chứng. File đã xoá (hết hạn lưu, rút đồng ý) → {@code 410 EVIDENCE_DELETED};
     * kho tắt/lỗi → {@code 503 STORAGE_UNAVAILABLE}. Nhật ký được ghi trước khi stream.
     */
    EvidenceDownload openEvidence(String attemptId, String evidenceId, UUID adminKeycloakId);

    /**
     * Duyệt: ghi định danh từ bản nháp vào hồ sơ, hồ sơ {@code VERIFIED} ({@code livenessVerified} khi
     * liveness PASS/REVIEW), task ánh xạ CIC, lượt {@code APPROVED} — một transaction; claim sau commit.
     */
    EkycAttemptDetailResponse approve(String attemptId, UUID adminKeycloakId, String note);

    /** Từ chối: lượt {@code REJECTED}, hồ sơ {@code FAILED} (người dùng làm lại được); claim sau commit. */
    EkycAttemptDetailResponse reject(String attemptId, UUID adminKeycloakId, String note);

    /** Luồng đọc một file bằng chứng; controller phải đóng {@link #object()} sau khi stream. */
    record EvidenceDownload(StoredObject object, String contentType, long contentLength) {
    }
}
