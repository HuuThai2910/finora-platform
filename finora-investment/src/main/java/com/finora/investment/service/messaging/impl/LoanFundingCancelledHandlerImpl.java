package com.finora.investment.service.messaging.impl;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.messaging.entity.ProcessedEvent;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.LoanFundingCancelledEventData;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.ListingCommitmentCanceller;
import com.finora.investment.service.messaging.LoanFundingCancelledHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
public class LoanFundingCancelledHandlerImpl implements LoanFundingCancelledHandler {

    private static final String ACTOR = "SYSTEM-LOAN-FUNDING-CANCELLED";
    private static final String ORDER_REASON = "LOAN_FUNDING_CANCELLED";
    private static final Map<String, String> REASON_DETAIL = Map.of(
            "BORROWER_WITHDREW", "Người vay đã rút hồ sơ trước khi ký; tiền giữ được trả lại ví",
            "CONTRACT_DECLINED", "Người vay từ chối hợp đồng; khoản vay không giải ngân, tiền giữ được trả lại ví",
            "CONTRACT_EXPIRED", "Hợp đồng hết hạn ký; khoản vay không giải ngân, tiền giữ được trả lại ví",
            "DISBURSEMENT_FAILED", "Giải ngân thất bại khi tiền chưa chuyển; tiền giữ được trả lại ví");

    private final ProcessedEventRepository processedEventRepository;
    private final MarketListingRepository marketListingRepository;
    private final InvestmentCommitmentRepository investmentCommitmentRepository;
    private final ListingCommitmentCanceller listingCommitmentCanceller;
    private final Clock clock;

    @Override
    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanFundingCancelledEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        String reasonDetail = REASON_DETAIL.get(data.reasonCode());
        if (reasonDetail == null || data.applicationNumber() == null || data.fundingRound() == null) {
            throw new IllegalArgumentException("LoanFundingCancelled thiếu business key hoặc reasonCode không hợp lệ");
        }
        boolean openWithdrawal = "BORROWER_WITHDREW".equals(data.reasonCode());
        MarketListing listing = openWithdrawal
                ? marketListingRepository.findByApplicationNumberAndFundingRoundForUpdate(
                        data.applicationNumber(), data.fundingRound()).orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy listing của hồ sơ đã rút"))
                : lockedAllocationListing(data);
        if (openWithdrawal) {
            requireOpenWithdrawal(listing, data);
        } else {
            requireSameAllocation(listing, data);
        }
        Instant now = clock.instant();
        if (listing.getStatus() != ListingStatus.CANCELLED) {
            requireNotDisbursed(listing);
            int cancelled = listingCommitmentCanceller.cancelActive(listing, ORDER_REASON, reasonDetail, ACTOR, now).count();
            listing.setStatus(ListingStatus.CANCELLED);
            listing.setCancelledAt(data.cancelledAt() == null ? now : data.cancelledAt());
            listing.setCancellationReason(data.reasonCode());
            listing.setUpdatedBy(ACTOR);
            listing.setUpdatedAt(now);
            marketListingRepository.saveAndFlush(listing);
            log.info("Hủy đợt gọi vốn theo Loan: listingId={}, applicationNumber={}, reasonCode={}, commitments={}",
                    listing.getId(), listing.getApplicationNumber(), data.reasonCode(), cancelled);
        }
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, "LoanFundingCancelled", 1, "finora-loan",
                data.applicationNumber(), occurredAt, now));
    }

    private MarketListing lockedAllocationListing(LoanFundingCancelledEventData data) {
        if (data.listingId() == null || data.allocationVersion() == null
                || data.allocationHash() == null || !data.allocationHash().matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("LoanFundingCancelled thiếu listing/allocation đã khóa");
        }
        return marketListingRepository.findByIdForUpdate(data.listingId())
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy listing của đợt gọi vốn bị hủy"));
    }

    private void requireOpenWithdrawal(MarketListing listing, LoanFundingCancelledEventData data) {
        boolean sameListing = listing.getLoanId().equals(data.loanApplicationId())
                && listing.getApplicationNumber().equals(data.applicationNumber())
                && listing.getFundingRound().equals(data.fundingRound());
        if (!sameListing) {
            throw InvestmentDomainException.conflict(
                    "FUNDING_CANCELLATION_MISMATCH", "Yêu cầu rút hồ sơ không khớp listing đang mở");
        }
        // Loan đã khóa Application ở REQUESTED trước khi nhận lệnh rút. Trong thời gian event đi qua Kafka,
        // lệnh cuối có thể vừa khóa allocation bên Investment; vẫn phải bù trừ nếu chưa disburse/finalize.
        if (listing.getStatus() != ListingStatus.OPEN
                && listing.getStatus() != ListingStatus.FULLY_FUNDED
                && listing.getStatus() != ListingStatus.CANCELLED) {
            throw InvestmentDomainException.conflict(
                    "LISTING_NOT_CANCELLABLE",
                    "Đợt gọi vốn không còn ở trạng thái có thể bù trừ yêu cầu rút hồ sơ");
        }
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
        if (listing.getDisbursedAt() != null || !investmentCommitmentRepository
                .findByListingIdAndStatus(listing.getId(), CommitmentStatus.FINALIZED).isEmpty()) {
            throw InvestmentDomainException.conflict(
                    "FUNDING_ALREADY_DISBURSED", "Khoản vay đã giải ngân nên không thể hủy đợt gọi vốn");
        }
    }
}
