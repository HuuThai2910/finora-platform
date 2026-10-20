package com.finora.payment.service.repayment;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.dto.response.RepaymentReconciliationResponse;
import com.finora.payment.integration.fineract.FineractRepaymentException;
import com.finora.payment.repository.repayment.PaymentRepaymentRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRepaymentReconciliationService {
    private static final List<PaymentRepaymentStatus> INCIDENT_STATUSES = List.of(
            PaymentRepaymentStatus.CORE_POSTING,
            PaymentRepaymentStatus.RECONCILIATION_REQUIRED,
            PaymentRepaymentStatus.FAILED);
    /** Khoảng an toàn từ lần ghi core gần nhất tới lúc được ghi lại (B32, D28). */
    private static final String REPOST_SAFE_AFTER = "finora.payment.core-repost-safe-after";

    private final PaymentRepaymentRepository repayments;
    private final PaymentRepaymentService repaymentService;
    private final Environment environment;

    /**
     * @param statuses lọc trong ba trạng thái sự cố; bỏ trống là cả ba. Web chỉ đọc việc còn mở ({@code CORE_POSTING},
     *                 {@code RECONCILIATION_REQUIRED}); {@code FAILED} là khoản đã hoàn tiền, không còn gì để xử lý.
     */
    @Transactional(readOnly = true)
    public PageResponse<RepaymentReconciliationResponse> list(List<PaymentRepaymentStatus> statuses, int page, int size) {
        requireAdmin();
        List<PaymentRepaymentStatus> filter = statuses == null || statuses.isEmpty() ? INCIDENT_STATUSES : statuses;
        if (!INCIDENT_STATUSES.containsAll(filter)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REPAYMENT_INCIDENT_STATUS_INVALID",
                    "Chỉ lọc được theo CORE_POSTING, RECONCILIATION_REQUIRED hoặc FAILED");
        }
        Duration safeAfter = repostSafeAfter();
        Page<RepaymentReconciliationResponse> result = repayments
                .findByStatusInOrderByUpdatedAtAscIdAsc(filter, PageRequest.of(page, size))
                .map(value -> RepaymentReconciliationResponse.from(value, safeAfter));
        return PageResponse.<RepaymentReconciliationResponse>builder()
                .content(result.getContent()).page(result.getNumber()).size(result.getSize())
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .last(result.isLast()).build();
    }

    /** Chỉ tra cứu transaction theo external reference; tuyệt đối không POST repayment lần hai. */
    public RepaymentReconciliationResponse reconcile(UUID repaymentId) {
        requireAdmin();
        PaymentRepayment value = repayments.findByRepaymentId(repaymentId).orElseThrow(() -> notFound());
        if (value.getStatus() != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "REPAYMENT_NOT_RECONCILABLE",
                    "Chỉ giao dịch RECONCILIATION_REQUIRED mới được đối soát thủ công");
        }
        repaymentService.reconcileCorePosting(value.getId());
        return current(repaymentId);
    }

    /**
     * B32 (D28): ghi lại vào Fineract khoản trả nợ RECONCILIATION_REQUIRED mà core chưa có, sau khoảng an toàn. Tra cứu
     * theo externalId trước; core đã có thì chỉ ghi nhận kết quả. Không tạo giao dịch thứ hai vì dùng đúng externalId.
     */
    public RepaymentReconciliationResponse repost(UUID repaymentId) {
        requireAdmin();
        PaymentRepayment value = repayments.findByRepaymentId(repaymentId).orElseThrow(() -> notFound());
        String actorId = SecurityUtils.getCurrentUserId();
        try {
            repaymentService.repostCorePosting(value.getId(), actorId, repostSafeAfter());
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
        return repayments.findByRepaymentId(repaymentId)
                .map(value -> RepaymentReconciliationResponse.from(value, safeAfter)).orElseThrow(() -> notFound());
    }

    private Duration repostSafeAfter() {
        return environment.getProperty(REPOST_SAFE_AFTER, Duration.class, Duration.ofMinutes(15));
    }

    private static void requireAdmin() {
        if (!SecurityUtils.hasRole("ROLE_ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED",
                    "Chỉ quản trị viên được truy cập đối soát trả nợ");
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "REPAYMENT_NOT_FOUND", "Không tìm thấy giao dịch trả nợ");
    }
}
