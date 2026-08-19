package com.finora.investment.service;

import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.investment.domain.InvestmentOrder;
import com.finora.investment.domain.OrderStatus;
import com.finora.investment.dto.request.CreateOrderRequest;
import com.finora.investment.repository.InvestmentOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final InvestmentOrderRepository orderRepo;

    @Transactional
    public InvestmentOrder create(CreateOrderRequest req) {
        var order = InvestmentOrder.builder()
                .investorId(req.investorId())
                .amount(req.amount())
                .remainingAmount(req.amount())
                .minRate(req.minRate())
                .maxRate(req.maxRate())
                .gradeFilter(req.gradeFilter())
                .build();
        return orderRepo.save(order);
    }

    @Transactional(readOnly = true)
    public Page<InvestmentOrder> findByInvestor(Long investorId, Pageable pageable) {
        return orderRepo.findByInvestorId(investorId, pageable);
    }

    @Transactional(readOnly = true)
    public InvestmentOrder findById(Long id) {
        return orderRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id));
    }

    @Transactional
    public void cancel(Long id) {
        var order = findById(id);
        if (!order.isPending()) {
            throw new BusinessException("Chỉ huỷ được lệnh đang PENDING hoặc PARTIALLY_MATCHED");
        }
        order.setStatus(OrderStatus.CANCELLED);
        orderRepo.save(order);
    }
}
