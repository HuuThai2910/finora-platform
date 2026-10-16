package com.finora.loan.repository.application;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
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

    /**
     * Khóa advisory trong transaction theo người vay để hai lần kiểm hạn mức dư nợ không cùng đọc tổng cũ. Khóa tự nhả
     * khi transaction kết thúc; không khóa bảng hay hàng của người vay khác.
     */
    @Query(value = "select cast(pg_advisory_xact_lock(hashtext(:key)) as varchar)", nativeQuery = true)
    Object lockBorrowerExposure(@Param("key") String key);

    /** Tổng số tiền đề nghị của các hồ sơ còn có thể thành khoản vay và chưa thành khoản vay. */
    @Query("""
            select coalesce(sum(application.requestedAmount), 0) from LoanApplication application
            where application.borrowerId = :borrowerId
              and application.status in :statuses
              and (application.fundingStatus is null or application.fundingStatus not in :closedFunding)
              and (application.termsConfirmationStatus is null or application.termsConfirmationStatus not in :closedTerms)
              and not exists (select loan.id from FinoraLoan loan where loan.loanApplicationId = application.id)
            """)
    java.math.BigDecimal sumOpenRequestedAmount(
            @Param("borrowerId") String borrowerId,
            @Param("statuses") Collection<LoanApplicationStatus> statuses,
            @Param("closedFunding") Collection<com.finora.loan.domain.application.LoanFundingStatus> closedFunding,
            @Param("closedTerms") Collection<com.finora.loan.domain.application.TermsConfirmationStatus> closedTerms);

    Optional<LoanApplication> findByApplicationNumber(String applicationNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from LoanApplication application where application.applicationNumber = :applicationNumber")
    Optional<LoanApplication> findByApplicationNumberForUpdate(@Param("applicationNumber") String applicationNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from LoanApplication application where application.id = :id")
    Optional<LoanApplication> findByIdForUpdate(@Param("id") Long id);

    Optional<LoanApplication> findByBorrowerIdAndIdempotencyKey(String borrowerId, String idempotencyKey);

    Optional<LoanApplication> findByAdminDecisionIdempotencyKey(String adminDecisionIdempotencyKey);

    Optional<LoanApplication> findByTermsConsentIdempotencyKey(String termsConsentIdempotencyKey);

    @Query("""
            select application.id from LoanApplication application
            where application.termsConfirmationStatus = com.finora.loan.domain.application.TermsConfirmationStatus.PENDING
              and application.termsExpiresAt <= :now
            order by application.termsExpiresAt asc, application.id asc
            """)
    List<Long> findDueTermsConfirmationIds(@Param("now") Instant now, Pageable pageable);

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
