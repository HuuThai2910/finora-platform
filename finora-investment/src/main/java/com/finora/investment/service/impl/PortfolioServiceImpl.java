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
import com.finora.investment.repository.InvestmentLoanServicingStateRepository;
import com.finora.investment.domain.note.InvestmentLoanServicingState;
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

    /** Phần vốn còn hiệu lực: đang tính vào gọi vốn hoặc đã bị Saga giải ngân khóa lại. */
    private static final List<CommitmentStatus> LIVE_COMMITMENT_STATUSES =
            List.of(CommitmentStatus.ACTIVE, CommitmentStatus.FINALIZED);

    /**
     * Note còn dư nợ mà nhà đầu tư đang nắm. DEFAULTED vẫn là tiền của nhà đầu tư nằm trong khoản
     * vay (và vẫn bán được trên chợ), nên phải hiện trong danh mục chứ không biến mất như trước.
     */
    private static final List<NoteStatus> HELD_NOTE_STATUSES =
            List.of(NoteStatus.ACTIVE, NoteStatus.DEFAULTED);

    private final InvestmentCommitmentRepository commitmentRepository;
    private final InvestmentNoteRepository noteRepository;
    private final MarketListingRepository listingRepository;
    private final InvestmentLoanServicingStateRepository servicingStateRepository;
    private final InvestmentMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PortfolioResponse portfolio() {
        String investorId = SecurityUtils.getCurrentUserId();

        List<InvestmentCommitment> commitments = commitmentRepository.findByInvestorIdAndStatusIn(
                investorId, LIVE_COMMITMENT_STATUSES);
        List<InvestmentNote> notes = noteRepository.findByInvestorIdAndStatusIn(investorId, HELD_NOTE_STATUSES);

        // Nạp listing của cả danh mục bằng một truy vấn thay vì tra từng khoản một,
        // vì một nhà đầu tư có thể nắm hàng trăm Note trên hàng chục khoản vay.
        Set<Long> listingIds = new HashSet<>();
        commitments.forEach(c -> listingIds.add(c.getListingId()));
        notes.forEach(n -> listingIds.add(n.getListingId()));
        Map<Long, MarketListing> listings = listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(MarketListing::getId, Function.identity()));

        BigDecimal pendingAmount = pendingAmountOf(commitments);

        Map<Long, List<InvestmentNote>> notesByLoan = notes.stream()
                .collect(Collectors.groupingBy(InvestmentNote::getLoanId));
        Map<Long, InvestmentLoanServicingState> servicingByLoan = servicingStateRepository
                .findByLoanApplicationIdIn(new ArrayList<>(notesByLoan.keySet())).stream()
                .collect(Collectors.toMap(InvestmentLoanServicingState::getLoanApplicationId,
                        Function.identity()));

        List<PortfolioPositionResponse> positions = new ArrayList<>();
        BigDecimal investedTotal = ZERO_MONEY;
        BigDecimal defaultedTotal = ZERO_MONEY;
        BigDecimal principalRepaidTotal = ZERO_MONEY;
        BigDecimal interestTotal = ZERO_MONEY;
        BigDecimal weightedRateNumerator = BigDecimal.ZERO;
        int activeNoteCount = 0;
        int defaultedNoteCount = 0;
        int defaultedPositionCount = 0;

        for (Map.Entry<Long, List<InvestmentNote>> entry : notesByLoan.entrySet()) {
            List<InvestmentNote> loanNotes = entry.getValue();
            InvestmentNote sample = loanNotes.get(0);
            MarketListing listing = listings.get(sample.getListingId());
            InvestmentLoanServicingState servicing = servicingByLoan.get(entry.getKey());

            BigDecimal principal = sum(loanNotes, InvestmentNote::getPrincipalAmount);
            BigDecimal outstanding = sum(loanNotes, InvestmentNote::getOutstandingPrincipal);
            BigDecimal repaid = sum(loanNotes, InvestmentNote::getPrincipalRepaid);
            BigDecimal interest = sum(loanNotes, InvestmentNote::getInterestReceived);

            // Mọi Note của một khoản vay đổi ACTIVE <-> DEFAULTED cùng lúc
            // (InvestmentNoteRepository.transitionStatusByLoan), nên một vị thế chỉ có một trạng thái.
            // Nếu gặp dữ liệu lệch thì nghiêng về báo nợ xấu: báo nhầm "đang trả bình thường" cho một
            // khoản nợ xấu hại nhà đầu tư hơn chiều ngược lại.
            boolean defaulted = loanNotes.stream().anyMatch(n -> n.getStatus() == NoteStatus.DEFAULTED);

            // Tiền đã nhận là tiền thật đã về ví, cộng trên mọi vị thế đang hiển thị.
            principalRepaidTotal = principalRepaidTotal.add(repaid);
            interestTotal = interestTotal.add(interest);
            if (defaulted) {
                defaultedTotal = defaultedTotal.add(outstanding);
                defaultedNoteCount += loanNotes.size();
                defaultedPositionCount++;
            } else {
                investedTotal = investedTotal.add(outstanding);
                activeNoteCount += loanNotes.size();
                // Lãi suất bình quân phải theo trọng số dư nợ: 100 triệu ở 12% và 1 triệu ở 20%
                // thì danh mục gần 12% chứ không phải 16%. Nợ xấu không còn sinh lãi đúng hạn nên
                // không kéo con số này lên.
                weightedRateNumerator = weightedRateNumerator.add(
                        outstanding.multiply(sample.getAnnualInterestRate()));
            }

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
                    defaulted ? NoteStatus.DEFAULTED.name() : NoteStatus.ACTIVE.name(),
                    servicing == null ? 0 : servicing.getDaysPastDue(),
                    servicing == null ? 1 : servicing.getDebtGroup(),
                    servicing == null || servicing.getOverdueAmount() == null
                            ? ZERO_MONEY.toPlainString() : servicing.getOverdueAmount().toPlainString(),
                    servicing == null ? "ACTIVE" : servicing.getStatus(),
                    servicing == null ? null : servicing.getMaturityDate(),
                    servicing == null ? null : servicing.getRiskDataAsOf()
            ));
        }

        positions.sort(Comparator.comparing(PortfolioPositionResponse::loanId));

        BigDecimal weightedAverageRate = investedTotal.signum() == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : weightedRateNumerator.divide(investedTotal, 4, RoundingMode.HALF_UP);

        return new PortfolioResponse(
                pendingAmount.toPlainString(),
                investedTotal.toPlainString(),
                principalRepaidTotal.toPlainString(),
                interestTotal.toPlainString(),
                principalRepaidTotal.add(interestTotal).toPlainString(),
                activeNoteCount,
                positions.size() - defaultedPositionCount,
                weightedAverageRate,
                positions,
                defaultedTotal.toPlainString(),
                defaultedNoteCount,
                defaultedPositionCount
        );
    }

    /** Các phần vốn đã cam kết của nhà đầu tư hiện tại, kể cả khoản vay chưa giải ngân. */
    @Override
    @Transactional(readOnly = true)
    public List<CommitmentResponse> myCommitments() {
        List<InvestmentCommitment> commitments = commitmentRepository.findByInvestorIdAndStatusIn(
                SecurityUtils.getCurrentUserId(),
                LIVE_COMMITMENT_STATUSES
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

    /**
     * Vốn chờ giải ngân: commitment còn hiệu lực mà chưa có Note nào được phát hành.
     *
     * <p>Không suy từ Note của chính nhà đầu tư như trước. Note có thể đã bán hết trên chợ (đổi
     * {@code investorId} sang người mua), đã CLOSED khi thu hồi đủ gốc, hoặc đang DEFAULTED; cả ba
     * trường hợp commitment vẫn ACTIVE/FINALIZED, và cách cũ "chưa có Note ACTIVE của tôi" cộng lại
     * số vốn đã giải ngân từ lâu thành "chờ giải ngân". Hỏi thẳng bảng Note theo commitmentId, bất
     * kể chủ hiện tại hay trạng thái, bằng một truy vấn cho cả danh mục.</p>
     */
    private BigDecimal pendingAmountOf(List<InvestmentCommitment> commitments) {
        if (commitments.isEmpty()) {
            // Không gửi "IN ()" xuống DB: chưa cam kết gì thì chắc chắn không có vốn chờ.
            return ZERO_MONEY;
        }
        Set<Long> issuedCommitmentIds = new HashSet<>(noteRepository.findCommitmentIdsWithNotes(
                commitments.stream().map(InvestmentCommitment::getId).toList()));
        return commitments.stream()
                .filter(c -> !issuedCommitmentIds.contains(c.getId()))
                .map(InvestmentCommitment::getAmount)
                .reduce(ZERO_MONEY, BigDecimal::add);
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
