package com.finora.payment.service.repayment;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.dto.response.RepaymentReconciliationResponse;
import com.finora.payment.repository.repayment.PaymentRepaymentRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRepaymentReconciliationService {
    private static final List<PaymentRepaymentStatus> INCIDENT_STATUSES = List.of(
            PaymentRepaymentStatus.CORE_POSTING,
            PaymentRepaymentStatus.RECONCILIATION_REQUIRED,
            PaymentRepaymentStatus.FAILED);

    private final PaymentRepaymentRepository repayments;
    private final PaymentRepaymentService repaymentService;

    @Transactional(readOnly = true)
    public PageResponse<RepaymentReconciliationResponse> list(int page, int size) {
        requireAdmin();
        Page<RepaymentReconciliationResponse> result = repayments
                .findByStatusInOrderByUpdatedAtAscIdAsc(INCIDENT_STATUSES, PageRequest.of(page, size))
                .map(RepaymentReconciliationResponse::from);
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
        return repayments.findByRepaymentId(repaymentId)
                .map(RepaymentReconciliationResponse::from).orElseThrow(() -> notFound());
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
