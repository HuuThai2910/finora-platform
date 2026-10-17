package com.finora.notification.repository;

import com.finora.notification.domain.LoanLender;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanLenderRepository extends JpaRepository<LoanLender, Long> {

    /** Dùng prefix của unique {@code (loan_application_id, investor_id)}. */
    List<LoanLender> findByLoanApplicationIdOrderByInvestorId(Long loanApplicationId);

    /**
     * Không xóa persistence context: dòng tham chiếu và lịch nhắc đang được quản lý trong cùng transaction phải giữ
     * nguyên; transaction không đọc LoanLender trước câu xóa nên không có bản cũ trong bộ nhớ.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from LoanLender l where l.loanApplicationId = :loanApplicationId")
    int deleteByLoan(@Param("loanApplicationId") Long loanApplicationId);
}
