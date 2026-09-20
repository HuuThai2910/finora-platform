package com.finora.investment.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.service.FundingSettingsService;
import com.finora.investment.service.MarketListingService;
import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.dto.response.FundingProgressResponse;
import com.finora.investment.dto.response.ListingInvestorResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.MarketListingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý bảng khoản vay đang gọi vốn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketListingServiceImpl implements MarketListingService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String SYSTEM_ACTOR = "SYSTEM-AUTO-LISTING";

    private final MarketListingRepository listingRepository;
    private final InvestmentCommitmentRepository commitmentRepository;
    private final InvestmentMapper mapper;
    private final FundingSettingsService settingsService;

    @Override
    @Transactional
    public MarketListingResponse createListingFrom(CreateListingRequest request) {
        return listingRepository
                .findByLoanId(request.getLoanId())
                .map(mapper::toListingResponse)
                .orElseGet(() -> mapper.toListingResponse(persistListing(request)));
    }

    private MarketListing persistListing(CreateListingRequest request) {
        Instant now = Instant.now();
        BigDecimal denomination = settingsService.currentNoteDenomination();
        int fundingDays = settingsService.currentFundingDays();
        BigDecimal targetAmount = floorToDenomination(request.getTargetAmount(), denomination);

        MarketListing listing = MarketListing.builder()
                .loanId(request.getLoanId())
                .contractNumber(request.getContractNumber())
                .productCode(request.getProductCode())
                .purpose(request.getPurpose())
                .region(request.getRegion())
                .creditGrade(request.getCreditGrade())
                .creditScore(request.getCreditScore())
                .targetAmount(targetAmount)
                .committedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .annualInterestRate(request.getAnnualInterestRate())
                .termMonths(request.getTermMonths())
                .repaymentMethod(request.getRepaymentMethod())
                .noteDenomination(denomination)
                .minInvestmentAmount(settingsService.currentMinInvestment())
                .status(ListingStatus.OPEN)
                .fundingRound(1)
                .fundingOpenedAt(now)
                .fundingClosesAt(now.plus(Duration.ofDays(fundingDays)))
                .createdBy(SYSTEM_ACTOR)
                .updatedBy(SYSTEM_ACTOR)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return listingRepository.save(listing);
    }

    private BigDecimal floorToDenomination(BigDecimal amount, BigDecimal denomination) {
        if (amount == null || denomination == null || denomination.signum() <= 0) {
            return amount;
        }
        BigDecimal floored = amount.subtract(amount.remainder(denomination));
        return floored.signum() > 0 ? floored : denomination;
    }

    /** Danh sách khoản vay đang mở gọi vốn, có lọc và phân trang. */
    @Transactional(readOnly = true)
    /**
     * Tìm khoản vay trên sàn.
     *
     * <p>{@code status} để trống thì mặc định chỉ trả khoản đang mở, vì app nhà đầu tư
     * chỉ đặt lệnh được vào đó. Màn quản trị truyền trạng thái cụ thể — hoặc
     * {@code ALL} để thấy mọi khoản — vì thao tác khóa vốn và phát hành Note chỉ áp
     * dụng cho khoản đã gọi đủ vốn.</p>
     */
    @Override
    public PageResponse<MarketListingResponse> search(
            String status,
            String creditGrade,
            BigDecimal minAnnualRate,
            Integer maxTermMonths,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "annualInterestRate")
        );

        Page<MarketListing> result = listingRepository.search(
                resolveStatus(status),
                normalize(creditGrade),
                minAnnualRate,
                maxTermMonths,
                pageable
        );

        return PageResponse.<MarketListingResponse>builder()
                .content(result.getContent().stream().map(mapper::toListingResponse).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public MarketListingResponse detail(Long listingId) {
        return mapper.toListingResponse(requireListing(listingId));
    }

    /**
     * Tiến độ gọi vốn của một khoản vay.
     *
     * <p>Số nhà đầu tư và số Note được tính từ danh sách commitment đã tải sẵn, không truy
     * vấn thêm cho từng dòng — tránh N+1 (performance-data-access.md).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public FundingProgressResponse progress(Long listingId) {
        MarketListing listing = requireListing(listingId);

        List<InvestmentCommitment> active =
                commitmentRepository.findByListingIdAndStatus(listing.getId(), CommitmentStatus.ACTIVE);
        List<InvestmentCommitment> finalized =
                commitmentRepository.findByListingIdAndStatus(listing.getId(), CommitmentStatus.FINALIZED);

        List<InvestmentCommitment> counted = java.util.stream.Stream
                .concat(active.stream(), finalized.stream())
                .toList();

        Set<String> investors = counted.stream()
                .map(InvestmentCommitment::getInvestorId)
                .collect(Collectors.toSet());
        int noteCount = counted.stream()
                .mapToInt(InvestmentCommitment::getNoteCount)
                .sum();

        BigDecimal remainingAmount = listing.getTargetAmount().subtract(listing.getCommittedAmount());
        BigDecimal fundedPercent = (listing.getTargetAmount() == null || listing.getTargetAmount().signum() == 0 || listing.getCommittedAmount() == null)
                ? BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP)
                : listing.getCommittedAmount()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(listing.getTargetAmount(), 2, java.math.RoundingMode.HALF_UP);

        return new FundingProgressResponse(
                listing.getId(),
                listing.getLoanId(),
                listing.getTargetAmount().toPlainString(),
                listing.getCommittedAmount().toPlainString(),
                remainingAmount.toPlainString(),
                fundedPercent,
                investors.size(),
                noteCount,
                listing.getStatus().name(),
                listing.getFundingClosesAt(),
                listing.getFullyFundedAt()
        );
    }

    /**
     * Đóng các listing đã hết hạn mà chưa gọi đủ vốn.
     *
     * <p>Xử lý theo lô có giới hạn để một lần chạy không quét toàn bộ bảng.</p>
     *
     * @return số listing vừa được đóng
     */
    @Override
    @Transactional
    public int closeExpiredListings(int batchSize) {
        Instant now = Instant.now();
        List<MarketListing> expired = listingRepository.findExpired(
                now, PageRequest.of(0, Math.min(Math.max(batchSize, 1), MAX_PAGE_SIZE)));

        int closed = 0;
        for (MarketListing listing : expired) {
            if (listing.getStatus() == ListingStatus.OPEN && !listing.getFundingClosesAt().isAfter(now)) {
                listing.setStatus(ListingStatus.CLOSED);
                listing.setUpdatedBy("SYSTEM");
                listing.setUpdatedAt(now);
                closed++;
            }
        }
        if (closed > 0) {
            log.info("Đóng listing hết hạn gọi vốn: count={}", closed);
        }
        return closed;
    }

    private MarketListing requireListing(Long listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay trên sàn"));
    }

    /**
     * Danh sách nhà đầu tư đã góp vốn vào một khoản vay.
     *
     * <p>Dành cho quản trị: khác {@code portfolio()} của nhà đầu tư ở chỗ trả về
     * {@code investorId}, nên chỉ lộ qua endpoint {@code /admin/**}.</p>
     *
     * <p>Trả cả phần vốn đã hủy để quản trị thấy lịch sử đầy đủ; trạng thái nằm trong
     * từng dòng nên phía giao diện tự phân biệt được.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public List<ListingInvestorResponse> investorsOf(Long listingId) {
        // Xác nhận khoản vay tồn tại trước, để mã sai trả 404 thay vì danh sách rỗng
        // — rỗng dễ bị đọc nhầm thành "chưa ai góp vốn".
        requireListing(listingId);

        return commitmentRepository.findByListingIdOrderByCreatedAtDesc(listingId).stream()
                .map(commitment -> new ListingInvestorResponse(
                        commitment.getId(),
                        commitment.getInvestorId(),
                        commitment.getAmount().toPlainString(),
                        commitment.getNoteCount(),
                        commitment.getSharePercent(),
                        commitment.getStatus().name(),
                        commitment.getCreatedAt()
                ))
                .toList();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    /**
     * Đổi tham số trạng thái của client thành {@link ListingStatus}.
     *
     * <p>Bỏ trống giữ nguyên hành vi cũ là chỉ trả khoản đang mở, để app nhà đầu tư
     * không đổi nghĩa. {@code ALL} trả null nghĩa là không lọc. Giá trị lạ bị từ chối
     * ngay thay vì im lặng trả danh sách rỗng, vì rỗng dễ bị đọc nhầm thành "sàn
     * không có khoản nào".</p>
     */
    private ListingStatus resolveStatus(String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            return ListingStatus.OPEN;
        }
        if ("ALL".equals(normalized)) {
            return null;
        }
        try {
            return ListingStatus.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw InvestmentDomainException.invalidInput(
                    "INVALID_LISTING_STATUS",
                    "Trạng thái không hợp lệ: " + status);
        }
    }
}
