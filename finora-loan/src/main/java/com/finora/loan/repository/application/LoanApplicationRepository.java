package com.finora.loan.repository.application;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Truy cập Loan Application mà không tải Product hoặc status history kèm theo.
 *
 * <p>Không cần {@code @Repository} vì Spring Data JPA tự tạo proxy cho interface
 * kế thừa {@link JpaRepository}.</p>
 */
public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    Optional<LoanApplication> findByApplicationNumber(String applicationNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from LoanApplication application where application.applicationNumber = :applicationNumber")
    Optional<LoanApplication> findByApplicationNumberForUpdate(@Param("applicationNumber") String applicationNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from LoanApplication application where application.id = :id")
    Optional<LoanApplication> findByIdForUpdate(@Param("id") Long id);

    Optional<LoanApplication> findByBorrowerIdAndIdempotencyKey(String borrowerId, String idempotencyKey);

    Optional<LoanApplication> findByAdminDecisionIdempotencyKey(String adminDecisionIdempotencyKey);

    /**
     * Phân trang hồ sơ theo borrower. Entity chỉ chứa scalar/embedded snapshot nên
     * việc đọc một trang không phát sinh N+1 sang Product hoặc history.
     */
    Page<LoanApplication> findByBorrowerId(String borrowerId, Pageable pageable);

    /** Hàng chờ admin luôn phân trang; assessment được batch-load ở service, không query theo từng row. */
    Page<LoanApplication> findByStatus(LoanApplicationStatus status, Pageable pageable);

    /** Worker chỉ lấy ID một batch nhỏ; entity được khóa riêng khi bắt đầu từng bước. */
    @Query("""
            select application.id from LoanApplication application
            where application.status in :statuses
            order by application.updatedAt asc, application.id asc
            """)
    List<Long> findIdsByStatusIn(
            @Param("statuses") Collection<com.finora.loan.domain.application.LoanApplicationStatus> statuses,
            Pageable pageable
    );
}
