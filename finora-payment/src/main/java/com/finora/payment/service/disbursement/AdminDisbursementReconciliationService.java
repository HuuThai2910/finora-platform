package com.finora.payment.service.disbursement;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.domain.disbursement.PaymentDisbursementStatus;
import com.finora.payment.dto.response.DisbursementReconciliationResponse;
import com.finora.payment.repository.PaymentDisbursementRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Màn sự cố giải ngân của admin (R2): lệnh chưa kết thúc và yêu cầu tra cứu lại provider cho lệnh đối soát đã hết lượt tự
 * động. Không có thao tác nào ở đây gọi chuyển tiền; worker đối soát chỉ tra cứu theo sagaId.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDisbursementReconciliationService {
    private static final List<PaymentDisbursementStatus> OPEN_STATUSES = List.of(PaymentDisbursementStatus.PROCESSING,
            PaymentDisbursementStatus.RETRY_PENDING, PaymentDisbursementStatus.RECONCILIATION_REQUIRED);

    private final PaymentDisbursementRepository disbursements;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<DisbursementReconciliationResponse> list(int page, int size) {
        requireAdmin();
        Page<DisbursementReconciliationResponse> result = disbursements
                .findByStatusInOrderByUpdatedAtAscIdAsc(OPEN_STATUSES, PageRequest.of(page, size))
                .map(DisbursementReconciliationResponse::from);
        return PageResponse.<DisbursementReconciliationResponse>builder()
                .content(result.getContent()).page(result.getNumber()).size(result.getSize())
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .last(result.isLast()).build();
    }

    @Transactional
    public DisbursementReconciliationResponse requestRecheck(UUID sagaId) {
        requireAdmin();
        PaymentDisbursement found = disbursements.findBySagaId(sagaId).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "DISBURSEMENT_NOT_FOUND", "Không tìm thấy lệnh giải ngân"));
        PaymentDisbursement value = disbursements.findByIdForUpdate(found.getId()).orElseThrow();
        if (value.getStatus() != PaymentDisbursementStatus.RECONCILIATION_REQUIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "DISBURSEMENT_NOT_RECONCILABLE",
                    "Chỉ lệnh giải ngân đang đối soát mới được tra cứu lại");
        }
        String actorId = SecurityUtils.getCurrentUserId();
        value.requestManualRecheck(actorId, clock.instant());
        log.warn("Admin yêu cầu tra cứu lại provider: sagaId={}, actor={}, manualRecheckCount={}",
                value.getSagaId(), actorId, value.getManualRecheckCount());
        return DisbursementReconciliationResponse.from(value);
    }

    private static void requireAdmin() {
        if (!SecurityUtils.hasRole("ROLE_ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED",
                    "Chỉ quản trị viên được truy cập đối soát giải ngân");
        }
    }
}
