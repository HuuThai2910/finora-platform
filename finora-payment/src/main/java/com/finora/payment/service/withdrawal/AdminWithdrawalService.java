package com.finora.payment.service.withdrawal;

import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.payment.domain.withdrawal.PaymentWithdrawal;
import com.finora.payment.domain.withdrawal.WithdrawalStatus;
import com.finora.payment.dto.response.AdminWithdrawalResponse;
import com.finora.payment.repository.withdrawal.PaymentWithdrawalRepository;
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
 * Hàng đợi sự cố rút tiền cho vận hành (R4): lệnh chưa có kết quả cuối, lâu nhất trước, và yêu cầu tra cứu lại provider
 * cho lệnh đã hết lượt tự động. Không thao tác nào ở đây gửi lệnh chi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminWithdrawalService {

    private static final List<WithdrawalStatus> INCIDENT = List.of(WithdrawalStatus.PROCESSING,
            WithdrawalStatus.PROVIDER_PENDING, WithdrawalStatus.RECONCILIATION_REQUIRED);

    private final PaymentWithdrawalRepository withdrawals;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminWithdrawalResponse> list(int page, int size) {
        SecurityUtils.requirePermission(OpsFunction.WITHDRAWAL.view());
        Page<AdminWithdrawalResponse> result = withdrawals
                .findByStatusInOrderByUpdatedAtAscIdAsc(INCIDENT, PageRequest.of(page, size))
                .map(AdminWithdrawalResponse::from);
        return PageResponse.<AdminWithdrawalResponse>builder().content(result.getContent()).page(result.getNumber())
                .size(result.getSize()).totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .last(result.isLast()).build();
    }

    @Transactional(readOnly = true)
    public AdminWithdrawalResponse find(UUID withdrawalId) {
        SecurityUtils.requirePermission(OpsFunction.WITHDRAWAL.view());
        return withdrawals.findByWithdrawalId(withdrawalId).map(AdminWithdrawalResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "WITHDRAWAL_NOT_FOUND",
                        "Không tìm thấy lệnh rút"));
    }

    @Transactional
    public AdminWithdrawalResponse recheck(UUID withdrawalId) {
        SecurityUtils.requirePermission(OpsFunction.WITHDRAWAL.action());
        PaymentWithdrawal value = withdrawals.findByWithdrawalIdForUpdate(withdrawalId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "WITHDRAWAL_NOT_FOUND",
                        "Không tìm thấy lệnh rút"));
        if (value.getStatus() != WithdrawalStatus.RECONCILIATION_REQUIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "WITHDRAWAL_NOT_RECONCILABLE",
                    "Chỉ lệnh rút đang đối soát mới được tra cứu lại");
        }
        String actorId = SecurityUtils.getCurrentUserId();
        value.requestManualRecheck(actorId, clock.instant());
        log.warn("Admin yêu cầu tra cứu lại lệnh rút: withdrawalId={}, actor={}, manualRecheckCount={}",
                withdrawalId, actorId, value.getManualRecheckCount());
        return AdminWithdrawalResponse.from(value);
    }
}
