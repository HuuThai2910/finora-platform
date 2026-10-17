package com.finora.loan.service.disbursement;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.disbursement.DisbursementSaga;
import com.finora.loan.domain.disbursement.DisbursementSagaStatus;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.disbursement.response.AdminDisbursementSagaResponse;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Màn sự cố giải ngân của admin (R2): xem saga theo trạng thái và đưa saga REPAIR_REQUIRED về ghi core lại sau khi vận hành
 * đã kiểm Fineract theo số hợp đồng. Không có thao tác nào ở đây chuyển tiền hay sửa dư nợ.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDisbursementSagaService {
    private final DisbursementSagaRepository sagas;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<AdminDisbursementSagaResponse> list(DisbursementSagaStatus status, int page, int size) {
        DisbursementSagaStatus value = status == null ? DisbursementSagaStatus.REPAIR_REQUIRED : status;
        return PageResponse.from(sagas.findByStatusOrderByUpdatedAtAscIdAsc(value, PageRequest.of(page, size))
                .map(AdminDisbursementSagaResponse::from));
    }

    @Transactional
    public AdminDisbursementSagaResponse requeue(UUID sagaId, String note) {
        DisbursementSaga saga = sagas.findBySagaIdForUpdate(sagaId).orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "DISBURSEMENT_SAGA_NOT_FOUND", "Không tìm thấy saga giải ngân"));
        if (saga.getStatus() != DisbursementSagaStatus.REPAIR_REQUIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "DISBURSEMENT_SAGA_NOT_REPAIRABLE",
                    "Chỉ saga đang chờ vận hành (REPAIR_REQUIRED) mới được ghi core lại");
        }
        String actorId = SecurityUtils.getCurrentUserId();
        saga.requeueAfterRepair(actorId, note, clock.instant());
        log.warn("Admin đưa saga giải ngân về ghi core lại: sagaId={}, contract={}, actor={}, repairCount={}",
                saga.getSagaId(), saga.getContractNumber(), actorId, saga.getRepairCount());
        return AdminDisbursementSagaResponse.from(saga);
    }
}
