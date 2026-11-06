package com.finora.payment.service.repayment.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.payment.domain.repayment.entity.PaymentRepayment;
import com.finora.payment.domain.repayment.enums.PaymentRepaymentStatus;
import com.finora.payment.dto.response.RepaymentReconciliationResponse;
import com.finora.payment.integration.fineract.FineractRepaymentException;
import com.finora.payment.repository.repayment.PaymentRepaymentRepository;
import com.finora.payment.service.repayment.AdminRepaymentReconciliationService;
import com.finora.payment.service.repayment.PaymentRepaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Màn đối soát & sự cố trả nợ của vận hành: khoản trả nợ kẹt ở bước ghi Fineract hoặc cần đối soát.
 * <p>
 * Mọi thao tác kiểm quyền chức năng Đối soát ({@code OpsFunction.RECONCILIATION}) rồi giao bước nghiệp vụ cho
 * {@code PaymentRepaymentService}. Khoảng an toàn trước khi ghi lại đọc từ
 * {@code finora.payment.core-repost-safe-after} (mặc định 15 phút).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRepaymentReconciliationServiceImpl implements AdminRepaymentReconciliationService {
    private static final List<PaymentRepaymentStatus> INCIDENT_STATUSES = List.of(
            PaymentRepaymentStatus.CORE_POSTING,
            PaymentRepaymentStatus.RECONCILIATION_REQUIRED,
            PaymentRepaymentStatus.FAILED);
    /** Khoảng an toàn từ lần ghi core gần nhất tới lúc được ghi lại (B32, D28). */
    private static final String REPOST_SAFE_AFTER = "finora.payment.core-repost-safe-after";

    private final PaymentRepaymentRepository paymentRepaymentRepository;
    private final PaymentRepaymentService paymentRepaymentService;
    private final Environment environment;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RepaymentReconciliationResponse> list(List<PaymentRepaymentStatus> statuses, int page, int size) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.view());
        List<PaymentRepaymentStatus> filter = statuses == null || statuses.isEmpty() ? INCIDENT_STATUSES : statuses;
        if (!INCIDENT_STATUSES.containsAll(filter)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REPAYMENT_INCIDENT_STATUS_INVALID",
                    "Chỉ lọc được theo CORE_POSTING, RECONCILIATION_REQUIRED hoặc FAILED");
        }
        Duration safeAfter = repostSafeAfter();
        Page<RepaymentReconciliationResponse> result = paymentRepaymentRepository
                .findByStatusInOrderByUpdatedAtAscIdAsc(filter, PageRequest.of(page, size))
                .map(value -> RepaymentReconciliationResponse.from(value, safeAfter));
        return PageResponse.<RepaymentReconciliationResponse>builder()
                .content(result.getContent()).page(result.getNumber()).size(result.getSize())
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .last(result.isLast()).build();
    }

    @Override
    public RepaymentReconciliationResponse reconcile(UUID repaymentId) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.action());
        PaymentRepayment value = paymentRepaymentRepository.findByRepaymentId(repaymentId)
                .orElseThrow(() -> notFound());
        if (value.getStatus() != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "REPAYMENT_NOT_RECONCILABLE",
                    "Chỉ giao dịch RECONCILIATION_REQUIRED mới được đối soát thủ công");
        }
        paymentRepaymentService.reconcileCorePosting(value.getId());
        return current(repaymentId);
    }

    @Override
    public RepaymentReconciliationResponse repost(UUID repaymentId) {
        SecurityUtils.requirePermission(OpsFunction.RECONCILIATION.action());
        PaymentRepayment value = paymentRepaymentRepository.findByRepaymentId(repaymentId)
                .orElseThrow(() -> notFound());
        String actorId = SecurityUtils.getCurrentUserId();
        try {
            paymentRepaymentService.repostCorePosting(value.getId(), actorId, repostSafeAfter());
        } catch (FineractRepaymentException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FINERACT_RECONCILIATION_UNAVAILABLE",
                    "Chưa tra cứu được Fineract theo mã giao dịch; khoản trả nợ giữ nguyên, thử lại sau");
        }
        RepaymentReconciliationResponse result = current(repaymentId);
        log.warn("Admin ghi lại repayment vào Fineract: repaymentId={}, actor={}, status={}, coreRepostCount={}",
                repaymentId, actorId, result.status(), result.coreRepostCount());
        return result;
    }

    private RepaymentReconciliationResponse current(UUID repaymentId) {
        Duration safeAfter = repostSafeAfter();
        return paymentRepaymentRepository.findByRepaymentId(repaymentId)
                .map(value -> RepaymentReconciliationResponse.from(value, safeAfter)).orElseThrow(() -> notFound());
    }

    private Duration repostSafeAfter() {
        return environment.getProperty(REPOST_SAFE_AFTER, Duration.class, Duration.ofMinutes(15));
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "REPAYMENT_NOT_FOUND", "Không tìm thấy giao dịch trả nợ");
    }
}
