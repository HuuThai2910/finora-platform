package com.finora.investment.service.transferdocument;

/**
 * Quét định kỳ lần khớp đã thanh toán để lập giấy xác nhận chuyển nhượng. Trạng thái nằm trong bảng nên service chết
 * giữa chừng thì lần quét sau làm tiếp; lần khớp đã thanh toán trước khi có tính năng này cũng được lập bù.
 */
public interface TransferDocumentWorker {

    void run();
}
