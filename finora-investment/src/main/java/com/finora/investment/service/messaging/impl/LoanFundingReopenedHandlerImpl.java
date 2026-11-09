package com.finora.investment.service.messaging.impl;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.common.enums.investment.ListingStatus;
import com.finora.common.enums.investment.OrderStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.messaging.entity.ProcessedEvent;
import com.finora.investment.domain.order.entity.InvestmentCommitment;
import com.finora.investment.domain.order.entity.InvestmentOrder;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.LoanFundingReopenedEventData;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.FundingSettingsService;
import com.finora.investment.service.messaging.LoanFundingReopenedHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gọi vốn bổ sung sau khi nhà đầu tư chưa ký rời hợp đồng (FUNDING-REOPEN-001).
 *
 * <p>Trong một transaction: khóa listing, đối chiếu đúng allocation đã khóa, hủy các commitment được Loan liệt kê
 * (lệnh CANCELLED kèm mã lý do), trừ tiến độ và mở lại listing cùng processed event. Không gọi Payment ở đây; worker
 * {@code FundingMaintenanceService.releasePendingHolds} nhả từng khoản giữ idempotent theo mã lệnh. Commitment còn lại
 * giữ ACTIVE với {@code contractBoundAt} nên không bị hủy qua lệnh trong lúc gọi vốn bổ sung.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanFundingReopenedHandlerImpl implements LoanFundingReopenedHandler {

    private static final String ACTOR = "SYSTEM-LOAN-FUNDING-REOPENED";
    private static final Map<String, String> REASON_DETAIL = Map.of(
            "INVESTOR_DECLINED", "Bạn đã từ chối ký hợp đồng; tiền giữ được trả lại ví",
            "LENDER_SIGNATURE_EXPIRED", "Hết hạn ký hợp đồng mà phần vốn chưa được ký; tiền giữ được trả lại ví");

    private final ProcessedEventRepository processedEventRepository;
    private final MarketListingRepository marketListingRepository;
    private final InvestmentCommitmentRepository investmentCommitmentRepository;
    private final InvestmentOrderRepository investmentOrderRepository;
    private final FundingSettingsService fundingSettingsService;
    private final Clock clock;

    @Override
    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanFundingReopenedEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        String reasonDetail = REASON_DETAIL.get(data.reasonCode());
        if (reasonDetail == null || data.listingId() == null || data.allocationVersion() == null
                || data.allocationHash() == null || !data.allocationHash().matches("^[0-9a-f]{64}$")
                || data.releasedCommitmentIds() == null || data.releasedCommitmentIds().isEmpty()) {
            throw new IllegalArgumentException("LoanFundingReopened thiếu allocation, phần vốn rời đi hoặc reasonCode hợp lệ");
        }
        MarketListing listing = marketListingRepository.findByIdForUpdate(data.listingId())
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy listing của đợt gọi vốn cần mở lại"));
        requireLockedAllocation(listing, data);
        Instant now = clock.instant();

        Set<Long> released = new HashSet<>(data.releasedCommitmentIds());
        List<InvestmentCommitment> commitments = investmentCommitmentRepository.findAllById(released);
        if (commitments.size() != released.size() || commitments.stream().anyMatch(commitment ->
                !commitment.getListingId().equals(listing.getId())
                        || commitment.getStatus() != CommitmentStatus.ACTIVE)) {
            throw InvestmentDomainException.conflict(
                    "REOPEN_COMMITMENT_MISMATCH", "Phần vốn Loan yêu cầu nhả không khớp phần vốn đang giữ của listing");
        }
        Map<Long, InvestmentOrder> orders = investmentOrderRepository
                .findAllById(commitments.stream().map(InvestmentCommitment::getOrderId).toList())
                .stream().collect(Collectors.toMap(InvestmentOrder::getId, Function.identity()));
        BigDecimal releasedAmount = BigDecimal.ZERO;
        for (InvestmentCommitment commitment : commitments) {
            commitment.setStatus(CommitmentStatus.CANCELLED);
            commitment.setCancelledAt(now);
            commitment.setUpdatedBy(ACTOR);
            commitment.setUpdatedAt(now);
            releasedAmount = releasedAmount.add(commitment.getAmount());

            InvestmentOrder order = orders.get(commitment.getOrderId());
            order.setStatus(OrderStatus.CANCELLED);
            order.setRejectedReasonCode(data.reasonCode());
            order.setRejectedReasonDetail(reasonDetail);
            // Worker nhả tiền tìm lệnh CANCELLED còn tham chiếu khoản giữ chưa nhả.
            if (order.getPaymentHoldReference() == null) {
                order.setPaymentHoldReference(commitment.getPaymentHoldReference());
            }
            order.setUpdatedBy(ACTOR);
            order.setUpdatedAt(now);
        }

        listing.setCommittedAmount(listing.getCommittedAmount().subtract(releasedAmount));
        listing.setStatus(ListingStatus.OPEN);
        listing.setFullyFundedAt(null);
        listing.setContractStatus("PENDING_REFUNDING");
        // Văn bản sẽ được phát hành lại theo allocation mới; hash cũ không còn là văn bản hiện hành.
        listing.setContractDocumentHash(null);
        // Hạn gọi vốn có thể đã qua trong lúc chờ ký: cho đợt bổ sung ít nhất một chu kỳ gọi vốn như listing mới.
        Instant minimumClose = now.plus(Duration.ofDays(fundingSettingsService.currentFundingDays()));
        if (listing.getFundingClosesAt().isBefore(minimumClose)) {
            listing.setFundingClosesAt(minimumClose);
        }
        listing.setUpdatedBy(ACTOR);
        listing.setUpdatedAt(now);
        marketListingRepository.saveAndFlush(listing);
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, "LoanFundingReopened", 1, "finora-loan", data.applicationNumber(), occurredAt, now));
        log.info("Mở lại gọi vốn bổ sung: listingId={}, applicationNumber={}, reasonCode={}, released={}",
                listing.getId(), listing.getApplicationNumber(), data.reasonCode(), commitments.size());
    }

    private static void requireLockedAllocation(MarketListing listing, LoanFundingReopenedEventData data) {
        boolean sameListing = listing.getLoanId().equals(data.loanApplicationId())
                && listing.getApplicationNumber().equals(data.applicationNumber())
                && listing.getFundingRound().equals(data.fundingRound())
                && data.allocationVersion().equals(listing.getAllocationVersion())
                && data.allocationHash().equals(listing.getAllocationHash());
        boolean sameContract = listing.getContractNumber() == null
                || listing.getContractNumber().equals(data.contractNumber());
        if (!sameListing || !sameContract) {
            throw InvestmentDomainException.conflict(
                    "FUNDING_REOPEN_MISMATCH", "Yêu cầu mở lại gọi vốn không khớp allocation đã khóa của listing");
        }
        // Đã có LoanDisbursed nghĩa là tiền đã capture: không còn phần vốn nào có thể nhả.
        if (listing.getStatus() != ListingStatus.FULLY_FUNDED || listing.getDisbursedAt() != null) {
            throw InvestmentDomainException.conflict(
                    "LISTING_NOT_REOPENABLE", "Chỉ listing đã đủ vốn và chưa giải ngân mới được mở lại");
        }
    }
}
