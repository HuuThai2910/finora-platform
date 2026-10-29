package com.finora.investment.service.messaging;

/**
 * Chuyển lại cho chủ hiện tại các khoản trả nợ Payment đã chia nhầm cho chủ cũ của Note
 * ({@link NoteRepaymentRedirect}). Gọi Payment ngoài transaction, ghi kết quả bằng transaction ngắn.
 */
public interface RepaymentRedirectService {

    /** @return số khoản đã chuyển xong trong lượt này */
    int settleDue(int batchSize);
}
