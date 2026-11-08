package com.finora.loan.service.funding.impl;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.exception.LoanDomainException;
import com.finora.loan.messaging.event.LoanFundingReopenedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.service.funding.LoanFundingReopenService;
import com.finora.loan.service.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Đưa khoản vay quay lại sàn gọi phần vốn còn thiếu sau khi nhà đầu tư chưa ký rời hợp đồng.
 *
 * <p>Không gọi Payment: Investment hủy đúng các commitment được liệt kê rồi worker nhả từng khoản giữ idempotent theo
 * mã lệnh, giống nhánh hủy gọi vốn.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanFundingReopenServiceImpl implements LoanFundingReopenService {

    private static final String EVENT_TYPE = "LoanFundingReopened";
    private static final String SYSTEM_ACTOR = "SYSTEM-FUNDING-REOPEN";

    private final LoanApplicationRepository loanApplicationRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reopen(
            Long applicationId,
            String contractNumber,
            List<ContractParty> released,
            List<ContractParty> retained,
            String reasonCode,
            Instant now
    ) {
        if (released == null || released.isEmpty()) {
            throw new IllegalArgumentException("Mở lại gọi vốn cần ít nhất một phần vốn rời hợp đồng");
        }
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> LoanDomainException.conflict(
                        "LOAN_APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ của đợt gọi vốn cần mở lại"));
        application.reopenFunding(SYSTEM_ACTOR, now);
        loanApplicationRepository.saveAndFlush(application);
        BigDecimal releasedAmount = released.stream()
                .map(ContractParty::getAllocationAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        outboxService.recordForPublication(
                "LoanApplication",
                application.getApplicationNumber(),
                EVENT_TYPE,
                1,
                new LoanFundingReopenedEventData(
                        application.getId(),
                        application.getApplicationNumber(),
                        application.getInvestmentListingId(),
                        application.getFundingRound(),
                        contractNumber,
                        application.getAllocationVersion(),
                        application.getAllocationHash(),
                        reasonCode,
                        released.stream().map(ContractParty::getCommitmentId).sorted().toList(),
                        releasedAmount.toPlainString(),
                        investorIds(released),
                        investorIds(retained),
                        now,
                        application.getBorrowerId()));
        log.info("Mở lại gọi vốn bổ sung: applicationNumber={}, contractNumber={}, reasonCode={}, released={}",
                application.getApplicationNumber(), contractNumber, reasonCode, released.size());
    }

    private static List<String> investorIds(List<ContractParty> parties) {
        return parties == null ? List.of() : parties.stream()
                .map(ContractParty::getPartyId).distinct().sorted().toList();
    }
}
