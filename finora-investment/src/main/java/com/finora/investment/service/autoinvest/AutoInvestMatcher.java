package com.finora.investment.service.autoinvest;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.common.enums.investment.OrderStatus;
import com.finora.investment.domain.autoinvest.AutoInvestConfig;
import com.finora.investment.domain.autoinvest.AutoInvestMatch;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.AutoInvestConfigRepository;
import com.finora.investment.repository.AutoInvestMatchRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.FundingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Khớp một listing vừa mở với các cấu hình Auto-Invest, theo thứ tự ai bật trước đi trước.
 *
 * <p>Không có {@code @Transactional}: mỗi lệnh đi qua {@link FundingService#placeOrderFor}, vốn
 * đã tự chia ba transaction ngắn quanh lời gọi Payment. Giữ transaction bao cả vòng lặp sẽ
 * khóa listing trong lúc chờ mạng — đúng điều 08-cross-service-flows cấm.</p>
 *
 * <p>Chạy lại an toàn: nhật ký unique {@code (investor_id, listing_id)} chặn xét lại một cặp,
 * còn khóa idempotency {@code AUTO-{listingId}} chặn đặt lệnh hai lần nếu lần trước dừng giữa
 * chừng.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoInvestMatcher {

    /** Lý do bỏ qua ghi vào nhật ký; mobile dịch sang tiếng Việt. */
    public static final String ALREADY_INVESTED = "ALREADY_INVESTED";
    public static final String BELOW_MINIMUM = "BELOW_MINIMUM";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String LISTING_FULL = "LISTING_FULL";
    public static final String LISTING_CLOSED = "LISTING_CLOSED";

    private static final Set<OrderStatus> LIVE_ORDER_STATUSES =
            Set.of(OrderStatus.PENDING_FUNDS, OrderStatus.COMMITTED);
    private static final Set<String> LISTING_FULL_CODES = Set.of("LISTING_INSUFFICIENT_REMAINING");
    private static final Set<String> LISTING_CLOSED_CODES =
            Set.of("LISTING_NOT_OPEN", "LISTING_WINDOW_CLOSED", "LISTING_NOT_FOUND");
    private static final Set<String> INSUFFICIENT_FUNDS_CODES =
            Set.of("PAYMENT_INSUFFICIENT_BALANCE", "INSUFFICIENT_BALANCE");

    private final MarketListingRepository listingRepository;
    private final AutoInvestConfigRepository configRepository;
    private final AutoInvestMatchRepository matchRepository;
    private final InvestmentOrderRepository orderRepository;
    private final FundingService fundingService;
    private final Clock clock;

    public static String idempotencyKey(Long listingId) {
        return "AUTO-" + listingId;
    }

    /**
     * Xét một listing. Trả {@code true} khi đã xét xong và listing được đánh dấu; {@code false}
     * khi phải dừng giữa chừng (Payment không phản hồi, lỗi bất ngờ) để lần quét sau làm tiếp.
     */
    public boolean processListing(Long listingId) {
        Optional<MarketListing> found = listingRepository.findById(listingId);
        if (found.isEmpty()) {
            return false;
        }
        MarketListing listing = found.get();
        if (!acceptsOrders(listing)) {
            return markProcessed(listingId);
        }

        List<AutoInvestConfig> candidates = configRepository.findCandidates(
                listingId, listing.getFundingOpenedAt(),
                listing.getAnnualInterestRate(), listing.getTermMonths());

        for (AutoInvestConfig config : candidates) {
            if (!config.accepts(listing.getCreditGrade(), listing.getAnnualInterestRate(),
                    listing.getTermMonths(), listing.getFundingOpenedAt())) {
                continue;
            }
            // Đọc lại mỗi vòng: lệnh vừa chốt và lệnh đặt tay song song đều làm vốn còn lại giảm.
            MarketListing current = listingRepository.findById(listingId).orElseThrow();
            if (!acceptsOrders(current)) {
                return markProcessed(listingId);
            }
            Step step = matchOne(config, current);
            if (step == Step.STOP_AND_MARK) {
                return markProcessed(listingId);
            }
            if (step == Step.STOP_AND_RETRY_LATER) {
                return false;
            }
        }
        return markProcessed(listingId);
    }

    private enum Step { CONTINUE, STOP_AND_MARK, STOP_AND_RETRY_LATER }

    private Step matchOne(AutoInvestConfig config, MarketListing listing) {
        String investorId = config.getInvestorId();
        Long listingId = listing.getId();
        String key = idempotencyKey(listingId);

        // Lệnh tự động của lần quét trước còn dở: làm tiếp đúng lệnh đó, cùng số tiền, để khóa
        // idempotency không bị coi là dùng lại cho nội dung khác.
        Optional<InvestmentOrder> previous = orderRepository.findByInvestorIdAndIdempotencyKey(investorId, key);
        BigDecimal amount;
        if (previous.isPresent()) {
            amount = previous.get().getAmount();
        } else {
            if (orderRepository.existsByListingIdAndInvestorIdAndStatusIn(listingId, investorId, LIVE_ORDER_STATUSES)) {
                record(AutoInvestMatch.skipped(investorId, listingId, ALREADY_INVESTED, null, clock.instant()));
                return Step.CONTINUE;
            }
            BigDecimal remaining = listing.getTargetAmount().subtract(listing.getCommittedAmount());
            amount = AutoInvestConfig.orderAmount(config.getAmountPerLoan(), remaining, listing.getNoteDenomination());
            if (amount.compareTo(listing.getMinInvestmentAmount()) < 0) {
                if (remaining.compareTo(config.getAmountPerLoan()) < 0) {
                    // Vốn còn lại không đủ một lệnh tối thiểu: không ai sau cũng khớp được.
                    record(AutoInvestMatch.skipped(investorId, listingId, LISTING_FULL, null, clock.instant()));
                    return Step.STOP_AND_MARK;
                }
                record(AutoInvestMatch.skipped(investorId, listingId, BELOW_MINIMUM, null, clock.instant()));
                return Step.CONTINUE;
            }
        }

        OrderResponse order;
        try {
            order = fundingService.placeOrderFor(investorId, listingId, key, new PlaceOrderRequest(amount));
        } catch (InvestmentDomainException failure) {
            if ("PAYMENT_UNAVAILABLE".equals(failure.getCode())) {
                log.warn("Auto-Invest dừng vì Payment không phản hồi: listingId={}", listingId);
                return Step.STOP_AND_RETRY_LATER;
            }
            if (LISTING_FULL_CODES.contains(failure.getCode())) {
                record(AutoInvestMatch.skipped(investorId, listingId, LISTING_FULL, null, clock.instant()));
                return Step.STOP_AND_MARK;
            }
            if (LISTING_CLOSED_CODES.contains(failure.getCode())) {
                return Step.STOP_AND_MARK;
            }
            record(AutoInvestMatch.skipped(investorId, listingId, failure.getCode(), null, clock.instant()));
            return Step.CONTINUE;
        } catch (RuntimeException failure) {
            log.error("Auto-Invest lỗi bất ngờ: listingId={}, exceptionType={}",
                    listingId, failure.getClass().getName());
            return Step.STOP_AND_RETRY_LATER;
        }

        if (OrderStatus.COMMITTED.name().equals(order.status())) {
            record(AutoInvestMatch.matched(investorId, listingId, amount, order.orderReference(), clock.instant()));
            log.info("Auto-Invest khớp: listingId={}, orderReference={}", listingId, order.orderReference());
            return Step.CONTINUE;
        }

        String code = order.rejectedReasonCode();
        if (LISTING_FULL_CODES.contains(code)) {
            record(AutoInvestMatch.skipped(investorId, listingId, LISTING_FULL, order.orderReference(), clock.instant()));
            return Step.STOP_AND_MARK;
        }
        if (LISTING_CLOSED_CODES.contains(code)) {
            record(AutoInvestMatch.skipped(investorId, listingId, LISTING_CLOSED, order.orderReference(), clock.instant()));
            return Step.STOP_AND_MARK;
        }
        String reason = INSUFFICIENT_FUNDS_CODES.contains(code) ? INSUFFICIENT_FUNDS
                : (code == null ? "ORDER_" + order.status() : code);
        record(AutoInvestMatch.skipped(investorId, listingId, reason, order.orderReference(), clock.instant()));
        return Step.CONTINUE;
    }

    private boolean acceptsOrders(MarketListing listing) {
        Instant now = clock.instant();
        return listing.getStatus() == ListingStatus.OPEN
                && listing.getFundingClosesAt().isAfter(now)
                && listing.getTargetAmount().subtract(listing.getCommittedAmount())
                        .compareTo(listing.getMinInvestmentAmount()) >= 0;
    }

    private boolean markProcessed(Long listingId) {
        listingRepository.markAutoInvestProcessed(listingId, clock.instant());
        return true;
    }

    /** Hai worker cùng xét một cặp thì dòng thứ hai vi phạm unique — bỏ qua là đúng. */
    private void record(AutoInvestMatch match) {
        try {
            matchRepository.save(match);
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Auto-Invest đã ghi nhật ký cặp này: listingId={}", match.getListingId());
        }
    }
}
