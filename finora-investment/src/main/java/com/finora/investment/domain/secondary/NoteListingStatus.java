package com.finora.investment.domain.secondary;

/**
 * Trạng thái một tin đăng bán Note trên chợ thứ cấp.
 *
 * <p>Đặt trong {@code finora-investment} chứ không đưa lên {@code finora-common}: chợ thứ cấp
 * là nghiệp vụ riêng của service này, chưa có service nào khác cần đọc trạng thái tin đăng bán.
 * Chỉ đẩy lên vùng dùng chung khi thật sự có bên thứ hai dùng tới.</p>
 */
public enum NoteListingStatus {

    /** Đang treo trên bảng tin, chờ người mua. */
    OPEN,

    /** Đã có người mua, Note đã đổi chủ. Trạng thái cuối. */
    SOLD,

    /** Người bán tự rút tin. Trạng thái cuối, nhưng Note có thể được treo lại sau. */
    CANCELLED
}
