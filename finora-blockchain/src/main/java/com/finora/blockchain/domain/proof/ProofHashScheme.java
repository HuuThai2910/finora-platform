package com.finora.blockchain.domain.proof;

import java.util.Arrays;

/**
 * Cách tính {@code payload_hash} ứng với {@code payload_version}. Người kiểm toán dùng đúng scheme này để tính lại hash
 * từ artifact nguồn (W3D, D52).
 */
public enum ProofHashScheme {
    /**
     * Trước W3D (chỉ REPAYMENT): SHA-256 của {@code data} dạng JSON compact giữ nguyên thứ tự field nhận được. Thứ tự
     * phụ thuộc cách producer/JSONB lưu payload nên chỉ còn dùng để kiểm lại bản ghi cũ (B51).
     */
    LEGACY_COMPACT_V1(1, "SHA-256 của data dạng JSON compact, giữ thứ tự field như event đã nhận"),
    /**
     * FINORA canonical JSON v2 (tập con RFC 8785): key sắp theo UTF-16, bỏ member có giá trị null, không khoảng trắng,
     * chỉ nhận số nguyên trong ±(2^53−1); chuỗi escape theo RFC 8785. Tiền luôn là chuỗi decimal nên không có số thực.
     */
    CANONICAL_JSON_V2(2, "SHA-256 của data chuẩn hóa: key sắp xếp, bỏ null, không khoảng trắng (RFC 8785, chỉ số nguyên)");

    /** Version dùng cho mọi proof mới. */
    public static final ProofHashScheme CURRENT = CANONICAL_JSON_V2;

    private final int version;
    private final String description;

    ProofHashScheme(int version, String description) {
        this.version = version;
        this.description = description;
    }

    public int version() {
        return version;
    }

    public String description() {
        return description;
    }

    public static ProofHashScheme fromVersion(int version) {
        return Arrays.stream(values())
                .filter(scheme -> scheme.version == version)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("payloadVersion " + version + " chưa có hash scheme"));
    }
}
