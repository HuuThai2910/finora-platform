package com.finora.investment.service.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.investment.service.PortfolioService;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.common.enums.investment.NoteStatus;
import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.dto.response.CommitmentResponse;
import com.finora.investment.dto.response.PortfolioPositionResponse;
import com.finora.investment.dto.response.PortfolioResponse;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tính toán danh mục đầu tư và các khoản đã cam kết của nhà đầu tư.
 */
@Service
@RequiredArgsConstructor
public class PortfolioServiceImpl implements PortfolioService {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final InvestmentCommitmentRepository commitmentRepository;
    private final InvestmentNoteRepository noteRepository;
    private final MarketListingRepository listingRepository;
    private final InvestmentMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PortfolioResponse portfolio() {
        String investorId = SecurityUtils.getCurrentUserId();

        List<InvestmentCommitment> commitments = commitmentRepository.findByInvestorIdAndStatusIn(
                investorId, List.of(CommitmentStatus.ACTIVE, CommitmentStatus.FINALIZED));
        List<InvestmentNote> notes = noteRepository.findByInvestorIdAndStatus(investorId, NoteStatus.ACTIVE);

        // Nạp listing của cả danh mục bằng một truy vấn thay vì tra từng khoản một,
        // vì một nhà đầu tư có thể nắm hàng trăm Note trên hàng chục khoản vay.
        Set<Long> listingIds = new HashSet<>();
        commitments.forEach(c -> listingIds.add(c.getListingId()));
        notes.forEach(n -> listingIds.add(n.getListingId()));
        Map<Long, MarketListing> listings = listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(MarketListing::getId, Function.identity()));

        // Vốn đang chờ chỉ tính commitment chưa có Note; sau khi phát hành Note thì phần
        // vốn đó đã chuyển sang "đang đầu tư", cộng cả hai sẽ thành đếm trùng.
        Set<Long> issuedCommitmentIds = notes.stream()
                .map(InvestmentNote::getCommitmentId)
                .collect(Collectors.toSet());
        BigDecimal pendingAmount = commitments.stream()
                .filter(c -> !issuedCommitmentIds.contains(c.getId()))
                .map(InvestmentCommitment::getAmount)
                .reduce(ZERO_MONEY, BigDecimal::add);

        Map<Long, List<InvestmentNote>> notesByLoan = notes.stream()
                .collect(Collectors.groupingBy(InvestmentNote::getLoanId));

        List<PortfolioPositionResponse> positions = new ArrayList<>();
        BigDecimal outstandingTotal = ZERO_MONEY;
        BigDecimal principalRepaidTotal = ZERO_MONEY;
        BigDecimal interestTotal = ZERO_MONEY;
        BigDecimal weightedRateNumerator = BigDecimal.ZERO;

        for (Map.Entry<Long, List<InvestmentNote>> entry : notesByLoan.entrySet()) {
            List<InvestmentNote> loanNotes = entry.getValue();
            InvestmentNote sample = loanNotes.get(0);
            MarketListing listing = listings.get(sample.getListingId());

            BigDecimal principal = sum(loanNotes, InvestmentNote::getPrincipalAmount);
            BigDecimal outstanding = sum(loanNotes, InvestmentNote::getOutstandingPrincipal);
            BigDecimal repaid = sum(loanNotes, InvestmentNote::getPrincipalRepaid);
            BigDecimal interest = sum(loanNotes, InvestmentNote::getInterestReceived);

            outstandingTotal = outstandingTotal.add(outstanding);
            principalRepaidTotal = principalRepaidTotal.add(repaid);
            interestTotal = interestTotal.add(interest);
            // Lãi suất bình quân phải theo trọng số dư nợ: 100 triệu ở 12% và 1 triệu ở 20%
            // thì danh mục gần 12% chứ không phải 16%.
            weightedRateNumerator = weightedRateNumerator.add(
                    outstanding.multiply(sample.getAnnualInterestRate()));

            positions.add(new PortfolioPositionResponse(
                    entry.getKey(),
                    sample.getListingId(),
                    listing == null ? null : listing.getPurpose(),
                    listing == null ? null : listing.getCreditGrade(),
                    sample.getAnnualInterestRate().toPlainString(),
                    sample.getTermMonths(),
                    loanNotes.size(),
                    principal.toPlainString(),
                    outstanding.toPlainString(),
                    repaid.toPlainString(),
                    interest.toPlainString(),
                    sharePercentOf(listing, principal),
                    NoteStatus.ACTIVE.name()
            ));
        }

        positions.sort(Comparator.comparing(PortfolioPositionResponse::loanId));

        BigDecimal weightedAverageRate = outstandingTotal.signum() == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : weightedRateNumerator.divide(outstandingTotal, 4, RoundingMode.HALF_UP);

        return new PortfolioResponse(
                pendingAmount.toPlainString(),
                outstandingTotal.toPlainString(),
                principalRepaidTotal.toPlainString(),
                interestTotal.toPlainString(),
                principalRepaidTotal.add(interestTotal).toPlainString(),
                notes.size(),
                positions.size(),
                weightedAverageRate,
                positions
        );
    }

    /** Các phần vốn đã cam kết của nhà đầu tư hiện tại, kể cả khoản vay chưa giải ngân. */
    @Override
    @Transactional(readOnly = true)
    public List<CommitmentResponse> myCommitments() {
        List<InvestmentCommitment> commitments = commitmentRepository.findByInvestorIdAndStatusIn(
                SecurityUtils.getCurrentUserId(),
                List.of(CommitmentStatus.ACTIVE, CommitmentStatus.FINALIZED)
        );

        Set<Long> listingIds = commitments.stream()
                .map(InvestmentCommitment::getListingId)
                .collect(Collectors.toSet());
        Map<Long, MarketListing> listings = listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(MarketListing::getId, Function.identity()));

        return commitments.stream()
                .map(c -> {
                    MarketListing listing = listings.get(c.getListingId());
                    return mapper.toCommitmentResponse(c, listing == null ? null : listing.getLoanId());
                })
                .toList();
    }

    private BigDecimal sum(List<InvestmentNote> notes, Function<InvestmentNote, BigDecimal> field) {
        return notes.stream().map(field).reduce(ZERO_MONEY, BigDecimal::add);
    }

    private BigDecimal sharePercentOf(MarketListing listing, BigDecimal principal) {
        if (listing == null || listing.getTargetAmount() == null || listing.getTargetAmount().signum() == 0 || principal == null) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return principal
                .multiply(BigDecimal.valueOf(100))
                .divide(listing.getTargetAmount(), 6, RoundingMode.HALF_UP);
    }
}
