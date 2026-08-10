package com.finora.loan.repository.application;

import com.finora.loan.domain.application.LoanApplicationStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Truy cập timeline trạng thái append-only của Loan Application.
 *
 * <p>Spring Data JPA tự đăng ký repository này thành Bean nên không cần thêm
 * {@code @Repository} trên interface.</p>
 */
public interface LoanApplicationStatusHistoryRepository extends JpaRepository<LoanApplicationStatusHistory, Long> {

    /**
     * Đọc timeline riêng theo Application thay vì nhúng collection vào aggregate,
     * giúp API list/detail không tải lịch sử ngoài nhu cầu.
     */
    Page<LoanApplicationStatusHistory> findByLoanApplicationId(Long applicationId, Pageable pageable);
}
