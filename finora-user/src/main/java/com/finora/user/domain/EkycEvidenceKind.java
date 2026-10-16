package com.finora.user.domain;

/** Loại object bằng chứng của một lượt xác minh ({@code ekyc_evidence.kind}). */
public enum EkycEvidenceKind {
    CCCD_FRONT,
    CCCD_BACK,
    LIVENESS_VIDEO,
    /** Khung bằng chứng AI trích từ video, hoặc ảnh tĩnh ở chế độ STILLS. */
    LIVENESS_FRAME,
    /** {@code result.json}: quyết định, mã lý do, điểm và SHA-256 các file — không chứa PII. */
    RESULT
}
