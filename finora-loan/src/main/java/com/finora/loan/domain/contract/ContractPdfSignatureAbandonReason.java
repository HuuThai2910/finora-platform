package com.finora.loan.domain.contract;

/** Lý do một lần ký PAdES bị bỏ; bên ký được thử lại bằng lần ký mới trên bản PDF mới nhất. */
public enum ContractPdfSignatureAbandonReason {
    /** Người ký từ chối hoặc hủy giao dịch trên ứng dụng của provider. */
    PROVIDER_REJECTED,
    /** Giá trị chữ ký provider trả về không khớp chứng thư và dữ liệu đã gửi; không được nhúng vào PDF. */
    PROVIDER_SIGNATURE_INVALID,
    /** Quá thời hạn phiên ký (SmartCA hết hạn khoảng 5 phút); giải phóng lượt ký cho bên khác. */
    SIGNING_SESSION_EXPIRED,
    /** Hợp đồng đã hết hạn hoặc bị từ chối trong lúc phiên ký còn mở. */
    CONTRACT_CLOSED
}
