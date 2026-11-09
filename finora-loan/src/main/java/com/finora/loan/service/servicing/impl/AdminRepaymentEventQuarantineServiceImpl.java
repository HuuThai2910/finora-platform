package com.finora.loan.service.servicing.impl;
import com.finora.loan.domain.servicing.entity.RepaymentEventQuarantine;
import com.finora.loan.domain.servicing.enums.RepaymentEventQuarantineStatus;
import com.finora.loan.dto.decision.response.ApplicationNumberResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.RepaymentEventQuarantineResponse;
import com.finora.loan.repository.servicing.RepaymentEventQuarantineRepository;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import com.finora.loan.service.servicing.AdminRepaymentEventQuarantineService;
import com.finora.loan.service.servicing.RepaymentDistributedHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Màn quarantine event trả nợ của admin: xem các event chưa khớp khoản vay và chạy lại từng event.
 */
@Service
@RequiredArgsConstructor
public class AdminRepaymentEventQuarantineServiceImpl implements AdminRepaymentEventQuarantineService {
    private final RepaymentEventQuarantineRepository repaymentEventQuarantineRepository;
    private final RepaymentDistributedHandler repaymentDistributedHandler;
    private final ApplicationNumberLookupService applicationNumberLookupService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RepaymentEventQuarantineResponse> list(RepaymentEventQuarantineStatus status,
            int page, int size) {
        var events = repaymentEventQuarantineRepository.findByStatusOrderByCreatedAtAscIdAsc(
                status == null ? RepaymentEventQuarantineStatus.PENDING : status,
                PageRequest.of(page, size));
        Map<Long, ApplicationNumberResponse> references = applicationNumberLookupService.referencesOf(
                        events.getContent().stream().map(RepaymentEventQuarantine::getLoanApplicationId).toList())
                .stream().collect(Collectors.toMap(ApplicationNumberResponse::id, Function.identity()));
        return PageResponse.from(events.map(event ->
                RepaymentEventQuarantineResponse.from(event, references.get(event.getLoanApplicationId()))));
    }

    @Override
    public RepaymentEventQuarantineResponse replay(java.util.UUID eventId) {
        repaymentDistributedHandler.replay(eventId);
        return repaymentEventQuarantineRepository.findByEventId(eventId).map(RepaymentEventQuarantineResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy repayment event trong quarantine"));
    }
}
