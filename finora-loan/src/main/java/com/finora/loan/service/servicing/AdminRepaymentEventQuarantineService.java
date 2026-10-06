package com.finora.loan.service.servicing;

import com.finora.loan.domain.servicing.RepaymentEventQuarantineStatus;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.servicing.response.RepaymentEventQuarantineResponse;
import com.finora.loan.repository.servicing.RepaymentEventQuarantineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRepaymentEventQuarantineService {
    private final RepaymentEventQuarantineRepository repository;
    private final RepaymentDistributedHandler handler;

    @Transactional(readOnly = true)
    public PageResponse<RepaymentEventQuarantineResponse> list(RepaymentEventQuarantineStatus status,
            int page, int size) {
        var result = repository.findByStatusOrderByCreatedAtAscIdAsc(
                status == null ? RepaymentEventQuarantineStatus.PENDING : status,
                PageRequest.of(page, size)).map(RepaymentEventQuarantineResponse::from);
        return PageResponse.from(result);
    }

    public RepaymentEventQuarantineResponse replay(java.util.UUID eventId) {
        handler.replay(eventId);
        return repository.findByEventId(eventId).map(RepaymentEventQuarantineResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy repayment event trong quarantine"));
    }
}
