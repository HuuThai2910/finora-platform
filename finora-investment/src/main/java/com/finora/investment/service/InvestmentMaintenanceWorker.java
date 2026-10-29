package com.finora.investment.service;

/**
 * Quét định kỳ các trạng thái không tự kết thúc: listing hết hạn gọi vốn, lệnh kẹt chờ giữ tiền,
 * khoản giữ chưa nhả, sổ lệnh của khoản vay đã tất toán và khoản trả nợ chia nhầm chủ cũ.
 */
public interface InvestmentMaintenanceWorker {

    void run();
}
