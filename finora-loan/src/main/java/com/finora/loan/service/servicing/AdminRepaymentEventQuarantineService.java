package com.finora.loan.service.servicing;

import com.finora.loan.domain.servicing.enums.RepaymentEventQuarantineStatus;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.servicing.response.RepaymentEventQuarantineResponse;

/**
 * Màn quarantine event trả nợ của admin: xem các event chưa khớp khoản vay và chạy lại từng event.
 */
public interface AdminRepaymentEventQuarantineService {

    /** Event trong quarantine theo trạng thái ({@code null} là PENDING), cũ nhất trước. */
    PageResponse<RepaymentEventQuarantineResponse> list(RepaymentEventQuarantineStatus status,
            int page, int size);

    /** Chạy lại một event đã quarantine rồi trả trạng thái mới của nó. */
    RepaymentEventQuarantineResponse replay(java.util.UUID eventId);
}
