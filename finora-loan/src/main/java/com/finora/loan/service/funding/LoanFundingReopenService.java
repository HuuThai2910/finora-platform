package com.finora.loan.service.funding;

import com.finora.loan.domain.contract.entity.ContractParty;

import java.time.Instant;
import java.util.List;

/**
 * Mở lại đợt gọi vốn đã đủ vốn khi nhà đầu tư chưa ký rời hợp đồng nhiều bên (FUNDING-REOPEN-001).
 *
 * <p>Khác {@link LoanFundingCancellationService}: hồ sơ không bị hủy, chỉ phần vốn rời đi được nhả; bên đã ký và bên
 * còn chờ ký giữ nguyên tiền giữ và vị trí trong hợp đồng.</p>
 */
public interface LoanFundingReopenService {

    /** Nhà đầu tư chưa ký chủ động từ chối hợp đồng. */
    String INVESTOR_DECLINED = "INVESTOR_DECLINED";

    /** Hết hạn ký khi còn nhà đầu tư chưa ký; các phần vốn chưa ký rời hợp đồng. */
    String LENDER_SIGNATURE_EXPIRED = "LENDER_SIGNATURE_EXPIRED";

    /**
     * Khóa hồ sơ, chuyển funding {@code FULLY_FUNDED -> REQUESTED} và ghi {@code LoanFundingReopened.v1} vào outbox
     * cùng transaction của caller (caller đã khóa Contract và đổi trạng thái các party).
     *
     * @param released phần vốn vừa rời hợp đồng (DECLINED/EXPIRED), không rỗng
     * @param retained phần vốn còn trong hợp đồng (đã ký hoặc còn chờ ký)
     */
    void reopen(
            Long applicationId,
            String contractNumber,
            List<ContractParty> released,
            List<ContractParty> retained,
            String reasonCode,
            Instant now
    );
}
