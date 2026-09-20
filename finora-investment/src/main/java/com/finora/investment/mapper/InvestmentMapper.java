package com.finora.investment.mapper;

import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.dto.response.CommitmentResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.dto.response.NoteResponse;
import com.finora.investment.dto.response.OrderResponse;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Chuyển entity sang DTO tại biên API.
 *
 * <p>Tiền được đổi sang chuỗi bằng {@code toPlainString()} để không xuất hiện ký hiệu mũ
 * với số lớn, và để client giữ nguyên độ chính xác thay vì parse thành số thực.</p>
 */
@Component
public class InvestmentMapper {

    public MarketListingResponse toListingResponse(MarketListing listing) {
        BigDecimal remainingAmount = calculateRemainingAmount(listing);
        BigDecimal fundedPercent = calculateFundedPercent(listing);
        return new MarketListingResponse(
                listing.getId(),
                listing.getLoanId(),
                listing.getPurpose(),
                listing.getRegion(),
                listing.getCreditGrade(),
                listing.getCreditScore(),
                listing.getTargetAmount().toPlainString(),
                listing.getCommittedAmount().toPlainString(),
                remainingAmount.toPlainString(),
                fundedPercent,
                listing.getAnnualInterestRate().toPlainString(),
                listing.getTermMonths(),
                listing.getRepaymentMethod(),
                listing.getNoteDenomination().toPlainString(),
                listing.getMinInvestmentAmount().toPlainString(),
                listing.getStatus().name(),
                listing.getFundingClosesAt()
        );
    }

    private BigDecimal calculateRemainingAmount(MarketListing listing) {
        if (listing.getTargetAmount() == null || listing.getCommittedAmount() == null) {
            return java.math.BigDecimal.ZERO;
        }
        return listing.getTargetAmount().subtract(listing.getCommittedAmount());
    }

    private BigDecimal calculateFundedPercent(MarketListing listing) {
        if (listing.getTargetAmount() == null || listing.getTargetAmount().signum() == 0 || listing.getCommittedAmount() == null) {
            return java.math.BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return listing.getCommittedAmount()
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(listing.getTargetAmount(), 2, java.math.RoundingMode.HALF_UP);
    }

    public OrderResponse toOrderResponse(InvestmentOrder order) {
        return new OrderResponse(
                order.getOrderReference(),
                order.getListingId(),
                order.getAmount().toPlainString(),
                order.getStatus().name(),
                order.getPaymentHoldReference(),
                order.getRejectedReasonCode(),
                order.getRejectedReasonDetail(),
                order.getCreatedAt()
        );
    }

    public CommitmentResponse toCommitmentResponse(InvestmentCommitment commitment, Long loanId) {
        return new CommitmentResponse(
                commitment.getId(),
                commitment.getListingId(),
                loanId,
                commitment.getAmount().toPlainString(),
                commitment.getNoteCount(),
                commitment.getNoteDenomination().toPlainString(),
                commitment.getSharePercent(),
                commitment.getStatus().name(),
                commitment.getCreatedAt()
        );
    }

    public NoteResponse toNoteResponse(InvestmentNote note) {
        return new NoteResponse(
                note.getNoteNumber(),
                note.getLoanId(),
                note.getPrincipalAmount().toPlainString(),
                note.getOutstandingPrincipal().toPlainString(),
                note.getPrincipalRepaid().toPlainString(),
                note.getInterestReceived().toPlainString(),
                note.getAnnualInterestRate().toPlainString(),
                note.getTermMonths(),
                note.getStatus().name(),
                note.getIssuedAt()
        );
    }
}
