package com.finora.investment.service.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.common.enums.investment.ListingStatus;
import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.common.enums.investment.OrderStatus;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentHoldResult;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.messaging.event.LoanFullyFundedEventData;
import com.finora.investment.service.InvestorEligibility;
import com.finora.investment.service.OrderTransactionService;
import com.finora.investment.service.messaging.AllocationSnapshotService;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt các transaction ngắn của luồng đặt lệnh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransactionServiceImpl implements OrderTransactionService {

    private final MarketListingRepository listingRepository;
    private final InvestmentOrderRepository orderRepository;
    private final InvestmentCommitmentRepository commitmentRepository;
    private final PaymentClient paymentClient;
    private final InvestmentMapper mapper;
    private final AllocationSnapshotService allocationSnapshotService;
    private final InvestmentOutboxService outboxService;
    private final InvestorEligibility eligibility;

    @Override
    @Transactional
    public InvestmentOrder createPendingOrder(
            Long listingId,
            String investorId,
            String idempotencyKey,
            String requestHash,
            PlaceOrderRequest request
    ) {
        MarketListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay trên sàn"));

        Instant now = Instant.now();
        eligibility.requireNotBorrower(listing, investorId);
        requirePlaceable(listing, request.amount(), now);

        InvestmentOrder order = InvestmentOrder.builder()
                .orderReference(nextOrderReference())
                .listingId(listingId)
                .investorId(investorId)
                .amount(request.amount())
                .status(OrderStatus.PENDING_FUNDS)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .riskAcknowledgedVersion(request.riskAcknowledgementVersion())
                .riskAcknowledgedAt(request.riskAcknowledgementVersion() == null ? null : now)
                .createdBy(investorId)
                .updatedBy(investorId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public OrderResponse confirmCommitment(Long orderId, Long listingId, String holdReference) {
        InvestmentOrder order = orderRepository.findById(orderId).orElseThrow();

        if (order.getStatus() == OrderStatus.COMMITTED) {
            return mapper.toOrderResponse(order);
        }
        if (order.getStatus() != OrderStatus.PENDING_FUNDS) {
            // Lệnh đã kết thúc ở đường khác trong lúc chờ Payment. Khoản vừa giữ không thuộc lệnh
            // nào đang chạy: ghi lại để worker nhả, không cam kết.
            if (order.getPaymentHoldReference() == null && order.getPaymentReleasedAt() == null) {
                order.setPaymentHoldReference(holdReference);
                order.setUpdatedAt(Instant.now());
            }
            return mapper.toOrderResponse(order);
        }

        MarketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay trên sàn"));

        Instant now = Instant.now();
        BigDecimal amount = order.getAmount();

        if (listing.getStatus() != ListingStatus.OPEN) {
            rejectAndRelease(order, holdReference, "LISTING_NOT_OPEN", "Khoản vay không còn nhận vốn", now);
            return mapper.toOrderResponse(order);
        }
        if (!listing.getFundingClosesAt().isAfter(now)) {
            rejectAndRelease(order, holdReference,
                    "LISTING_WINDOW_CLOSED", "Đã hết hạn gọi vốn cho khoản vay này", now);
            return mapper.toOrderResponse(order);
        }

        BigDecimal remaining = listing.getTargetAmount().subtract(listing.getCommittedAmount());
        if (amount.compareTo(remaining) > 0) {
            rejectAndRelease(order, holdReference, "LISTING_INSUFFICIENT_REMAINING",
                    "Khoản vay chỉ còn nhận thêm " + remaining.toPlainString() + " đồng", now);
            log.info("Từ chối lệnh và nhả tiền: orderReference={}, reason=LISTING_INSUFFICIENT_REMAINING",
                    order.getOrderReference());
            return mapper.toOrderResponse(order);
        }

        listing.setCommittedAmount(listing.getCommittedAmount().add(amount));
        listing.setUpdatedAt(now);

        boolean fullyFunded = listing.getCommittedAmount().compareTo(listing.getTargetAmount()) == 0;
        listingRepository.save(listing);

        order.setStatus(OrderStatus.COMMITTED);
        order.setPaymentHoldReference(holdReference);
        order.setPaymentHoldAt(now);
        order.setUpdatedAt(now);

        int noteCount = order.getAmount().divide(listing.getNoteDenomination(), 0, java.math.RoundingMode.DOWN).intValueExact();
        BigDecimal sharePercent = order.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .divide(listing.getTargetAmount(), 6, java.math.RoundingMode.HALF_UP);

        commitmentRepository.saveAndFlush(InvestmentCommitment.builder()
                .orderId(order.getId())
                .listingId(listing.getId())
                .investorId(order.getInvestorId())
                .amount(order.getAmount())
                .noteCount(noteCount)
                .noteDenomination(listing.getNoteDenomination())
                .sharePercent(sharePercent)
                .status(CommitmentStatus.ACTIVE)
                .paymentHoldReference(holdReference)
                .createdBy(order.getInvestorId())
                .updatedBy(order.getInvestorId())
                .createdAt(now)
                .updatedAt(now)
                .build());

        if (fullyFunded) {
            var snapshot = allocationSnapshotService.snapshot(
                    commitmentRepository.findByListingIdAndStatusOrderByIdAsc(
                            listing.getId(), CommitmentStatus.ACTIVE));
            long allocationVersion = 1L;
            listing.setStatus(ListingStatus.FULLY_FUNDED);
            listing.setFullyFundedAt(now);
            listing.setAllocationVersion(allocationVersion);
            listing.setAllocationHash(snapshot.hash());
            listingRepository.saveAndFlush(listing);
            outboxService.record(
                    "MarketListing",
                    listing.getApplicationNumber(),
                    "LoanFullyFunded",
                    2,
                    new LoanFullyFundedEventData(
                            listing.getLoanId(),
                            listing.getApplicationNumber(),
                            listing.getId(),
                            listing.getFundingRound(),
                            listing.getCommittedAmount().toPlainString(),
                            "VND",
                            allocationVersion,
                            snapshot.hash(),
                            now,
                            snapshot.allocations()
                    )
            );
            log.info("Khoản vay đã gọi đủ vốn: loanId={}, listingId={}",
                    listing.getLoanId(), listing.getId());
        }

        return mapper.toOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse rejectForFailedHold(Long orderId, PaymentHoldResult hold) {
        InvestmentOrder order = orderRepository.findById(orderId).orElseThrow();
        Instant now = Instant.now();
        rejectOrder(order, hold.getErrorCode(), hold.getErrorMessage(), now);
        return mapper.toOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(String orderReference) {
        InvestmentOrder order = orderRepository.findByOrderReference(orderReference)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "ORDER_NOT_FOUND", "Không tìm thấy lệnh đầu tư"));
        
        String currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId != null && !currentUserId.equals(order.getInvestorId())) {
            throw InvestmentDomainException.forbidden("ACCESS_DENIED", "Bạn không có quyền thao tác trên lệnh này");
        }

        InvestmentCommitment commitment = commitmentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> InvestmentDomainException.conflict(
                        "COMMITMENT_NOT_FOUND", "Lệnh chưa có phần vốn để hủy"));

        if (commitment.getStatus() == CommitmentStatus.CANCELLED) {
            return mapper.toOrderResponse(order);
        }
        if (commitment.getStatus() == CommitmentStatus.FINALIZED) {
            throw InvestmentDomainException.conflict(
                    "COMMITMENT_ALREADY_FINALIZED",
                    "Khoản vay đang giải ngân, không thể hủy phần vốn"
            );
        }

        MarketListing listing = listingRepository.findByIdForUpdate(commitment.getListingId())
                .orElseThrow();

        if (listing.getStatus() == ListingStatus.FULLY_FUNDED) {
            throw InvestmentDomainException.conflict(
                    "FUNDED_ALLOCATION_LOCKED",
                    "Danh sách phân bổ đã khóa để lập hợp đồng, không thể hủy trực tiếp"
            );
        }

        Instant now = Instant.now();
        commitment.setStatus(CommitmentStatus.CANCELLED);
        commitment.setCancelledAt(now);
        commitment.setUpdatedAt(now);

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(now);

        listing.setCommittedAmount(listing.getCommittedAmount().subtract(commitment.getAmount()));
        listing.setUpdatedAt(now);
        listingRepository.save(listing);

        paymentClient.release(commitment.getPaymentHoldReference(), order.getOrderReference());
        order.setPaymentReleasedAt(now);
        order.setUpdatedAt(now);

        log.info("Hủy lệnh và hoàn vốn: orderReference={}", orderReference);
        return mapper.toOrderResponse(order);
    }

    private void rejectOrder(InvestmentOrder order, String reasonCode, String reasonDetail, Instant now) {
        order.setStatus(OrderStatus.REJECTED);
        order.setRejectedReasonCode(reasonCode);
        order.setRejectedReasonDetail(reasonDetail);
        order.setUpdatedAt(now);
    }

    @Override
    @Transactional
    public void markHoldReleased(Long orderId) {
        orderRepository.findById(orderId)
                .filter(order -> order.getPaymentReleasedAt() == null)
                .ifPresent(order -> {
                    Instant now = Instant.now();
                    order.setPaymentReleasedAt(now);
                    order.setUpdatedAt(now);
                });
    }

    /**
     * Từ chối lệnh đã giữ tiền và thử nhả ngay. Ghi {@code paymentHoldReference} để nếu lần nhả này
     * lỗi thì worker ({@code findReleasePending}) nhả lại — chỉ đánh dấu đã nhả khi Payment xác nhận.
     */
    private void rejectAndRelease(
            InvestmentOrder order, String holdReference, String reasonCode, String reasonDetail, Instant now) {
        order.setPaymentHoldReference(holdReference);
        rejectOrder(order, reasonCode, reasonDetail, now);
        if (releaseQuietly(order, holdReference)) {
            order.setPaymentReleasedAt(now);
        }
    }

    private boolean releaseQuietly(InvestmentOrder order, String holdReference) {
        try {
            paymentClient.release(holdReference, order.getOrderReference());
            return true;
        } catch (RuntimeException e) {
            log.warn("Chưa nhả được tiền giữ, worker sẽ thử lại: orderReference={}, exceptionType={}",
                    order.getOrderReference(), e.getClass().getName());
            return false;
        }
    }

    private void requirePlaceable(MarketListing listing, BigDecimal amount, Instant now) {
        if (listing.getStatus() != ListingStatus.OPEN) {
            throw InvestmentDomainException.conflict("LISTING_NOT_OPEN", "Khoản vay không còn nhận vốn");
        }
        if (listing.getFundingClosesAt().isBefore(now)) {
            throw InvestmentDomainException.conflict("LISTING_WINDOW_CLOSED", "Đã hết hạn gọi vốn");
        }
        BigDecimal remaining = listing.getTargetAmount().subtract(listing.getCommittedAmount());
        // Phần còn thiếu nhỏ hơn mức tối thiểu thì vẫn cho lấp đúng phần đó; chặn cứng theo mức
        // tối thiểu sẽ để lại một khoản lẻ không ai mua được và khoản vay chết ở 9x%.
        if (amount.compareTo(listing.getMinInvestmentAmount()) < 0 && amount.compareTo(remaining) != 0) {
            throw InvestmentDomainException.invalidInput(
                    "ORDER_BELOW_MINIMUM", "Số tiền đầu tư thấp hơn mức tối thiểu");
        }
        if (amount.remainder(listing.getNoteDenomination()).signum() != 0) {
            throw InvestmentDomainException.invalidInput(
                    "ORDER_NOT_DIVISIBLE", "Số tiền đầu tư phải chia hết cho mệnh giá Note");
        }
        if (amount.compareTo(remaining) > 0) {
            throw InvestmentDomainException.conflict(
                    "LISTING_INSUFFICIENT_REMAINING",
                    "Khoản vay chỉ còn nhận thêm " + remaining.toPlainString() + " đồng"
            );
        }
    }

    private String nextOrderReference() {
        return "IO-" + Instant.now().toEpochMilli() + "-"
                + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current()
                        .nextInt(0x10000, 0xFFFFF));
    }
}
