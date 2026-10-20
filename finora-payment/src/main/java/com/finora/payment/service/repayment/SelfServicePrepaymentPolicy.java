package com.finora.payment.service.repayment;

import com.finora.common.exception.BusinessException;
import com.finora.payment.domain.servicing.PaymentLoanAccount;
import org.springframework.http.HttpStatus;

/**
 * D17: chỉ khoản vay core V4 (lãi theo dư nợ và số ngày thực tế) được trả trước một phần hoặc tất toán trên ứng dụng.
 *
 * <p>Core V1–V3 tính trước lãi cho cả kỳ hạn: báo giá Fineract của các khoản vay này gồm cả lãi các tháng tương lai
 * (B28/B29), trái với điều khoản "không thu lãi của các tháng tương lai" đã công bố. Chặn rõ ràng tốt hơn thu sai.</p>
 */
final class SelfServicePrepaymentPolicy {

    static final String LEGACY_LOAN_CODE = "PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN";

    private SelfServicePrepaymentPolicy() {
    }

    static void requireDailyInterestCore(PaymentLoanAccount account) {
        if (!account.dailyInterestCore()) {
            throw new BusinessException(HttpStatus.CONFLICT, LEGACY_LOAN_CODE,
                    "Khoản vay này dùng sản phẩm cũ tính trước lãi cho cả kỳ hạn nên chưa hỗ trợ trả trước hoặc tất toán "
                            + "trên ứng dụng. Bạn vẫn trả từng kỳ theo lịch như bình thường");
        }
    }
}
