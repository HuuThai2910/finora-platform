package com.finora.investment.domain.transferdocument;

/**
 * Trạng thái giấy xác nhận chuyển nhượng của một lần khớp.
 *
 * <p>{@code PENDING} khi lần khớp vừa thanh toán xong; worker lập PDF, đóng dấu số rồi chuyển {@code ISSUED} cùng
 * event neo hash lên blockchain. {@code FAILED} khi đã thử hết số lần cho phép; quản trị viên bấm thử lại.</p>
 */
public enum NoteTransferDocumentStatus {
    PENDING,
    ISSUED,
    FAILED
}
