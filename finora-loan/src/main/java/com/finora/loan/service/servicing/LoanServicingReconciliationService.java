package com.finora.loan.service.servicing;

import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.enums.ReconciliationIncidentType;
import com.finora.loan.domain.servicing.model.LoanServicingSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Kiểm tra bất biến identity/tài chính trước khi cho snapshot core ghi đè projection.
 */
public interface LoanServicingReconciliationService {

    /**
     * Đối chiếu khoản vay với snapshot core: mở hoặc cập nhật incident cho từng sai lệch, đóng các
     * incident đang mở khi đã khớp.
     *
     * @return các loại sai lệch; rỗng nếu snapshot hợp lệ
     */
    List<ReconciliationIncidentType> inspect(
            FinoraLoan loan, LoanServicingSnapshot snapshot, Instant now);
}
