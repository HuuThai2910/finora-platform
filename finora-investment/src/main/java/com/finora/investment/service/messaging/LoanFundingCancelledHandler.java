package com.finora.investment.service.messaging;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.LoanFundingCancelledEventData;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.impl.ListingCommitmentCanceller;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bồi hoàn cho đợt gọi vốn đã đủ vốn nhưng Loan xác nhận sẽ không giải ngân.
 *
 * <p>Trong một transaction: khóa listing, đối chiếu đúng allocation đã khóa, hủy phần vốn/lệnh và đánh dấu
 * listing CANCELLED cùng processed event. Không gọi Payment ở đây; worker
 * {@code FundingMaintenanceService.releasePendingHolds} nhả từng khoản giữ idempotent theo mã lệnh, nên
 * Payment tạm lỗi không làm rollback quyết định hủy và cũng không trả tiền hai lần.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanFundingCancelledHandler {

    private static final String ACTOR = "SYSTEM-LOAN-FUNDING-CANCELLED";
    private static final String ORDER_REASON = "LOAN_FUNDING_CANCELLED";
    private static final Map<String, String> REASON_DETAIL = Map.of(
            "CONTRACT_DECLINED", "Người vay từ chối hợp đồng; khoản vay không giải ngân, tiền giữ được trả lại ví",
            "CONTRACT_EXPIRED", "Hợp đồng hết hạn ký; khoản vay không giải ngân, tiền giữ được trả lại ví",
            "DISBURSEMENT_FAILED", "Giải ngân thất bại khi tiền chưa chuyển; tiền giữ được trả lại ví");

    private final ProcessedEventRepository processedEventRepository;
    private final MarketListingRepository listingRepository;
    private final InvestmentCommitmentRepository commitmentRepository;
    private final ListingCommitmentCanceller commitmentCanceller;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanFundingCancelledEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        String reasonDetail = REASON_DETAIL.get(data.reasonCode());
        if (reasonDetail == null || data.listingId() == null || data.allocationVersion() == null
                || data.allocationHash() == null || !data.allocationHash().matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("LoanFundingCancelled thiếu listing/allocation hoặc reasonCode không hợp lệ");
        }
        MarketListing listing = listingRepository.findByIdForUpdate(data.listingId())
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy listing của đợt gọi vốn bị hủy"));
        requireSameAllocation(listing, data);
        Instant now = clock.instant();
        if (listing.getStatus() != ListingStatus.CANCELLED) {
            requireNotDisbursed(listing);
            int cancelled = commitmentCanceller.cancelActive(listing, ORDER_REASON, reasonDetail, ACTOR, now).count();
            listing.setStatus(ListingStatus.CANCELLED);
            listing.setCancelledAt(data.cancelledAt() == null ? now : data.cancelledAt());
            listing.setCancellationReason(data.reasonCode());
            listing.setUpdatedBy(ACTOR);
            listing.setUpdatedAt(now);
            listingRepository.saveAndFlush(listing);
            log.info("Hủy đợt gọi vốn theo Loan: listingId={}, applicationNumber={}, reasonCode={}, commitments={}",
                    listing.getId(), listing.getApplicationNumber(), data.reasonCode(), cancelled);
        }
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, "LoanFundingCancelled", 1, "finora-loan",
                data.applicationNumber(), occurredAt, now));
    }

    private void requireSameAllocation(MarketListing listing, LoanFundingCancelledEventData data) {
        boolean sameListing = listing.getLoanId().equals(data.loanApplicationId())
                && listing.getApplicationNumber().equals(data.applicationNumber())
                && listing.getFundingRound().equals(data.fundingRound())
                && data.allocationVersion().equals(listing.getAllocationVersion())
                && data.allocationHash().equals(listing.getAllocationHash());
        boolean sameContract = listing.getContractNumber() == null
                || listing.getContractNumber().equals(data.contractNumber());
        if (!sameListing || !sameContract) {
            throw InvestmentDomainException.conflict(
                    "FUNDING_CANCELLATION_MISMATCH", "Yêu cầu hủy gọi vốn không khớp allocation đã khóa của listing");
        }
        if (listing.getStatus() != ListingStatus.FULLY_FUNDED && listing.getStatus() != ListingStatus.CANCELLED) {
            throw InvestmentDomainException.conflict(
                    "LISTING_NOT_FULLY_FUNDED", "Chỉ listing đã đủ vốn mới có tiền giữ cần trả khi hủy");
        }
    }

    private void requireNotDisbursed(MarketListing listing) {
        // Đã có LoanDisbursed hoặc phần vốn FINALIZED nghĩa là tiền đã capture: hủy lúc này sẽ trả tiền hai lần.
        if (listing.getDisbursedAt() != null || !commitmentRepository
                .findByListingIdAndStatus(listing.getId(), CommitmentStatus.FINALIZED).isEmpty()) {
            throw InvestmentDomainException.conflict(
                    "FUNDING_ALREADY_DISBURSED", "Khoản vay đã giải ngân nên không thể hủy đợt gọi vốn");
        }
    }
}
