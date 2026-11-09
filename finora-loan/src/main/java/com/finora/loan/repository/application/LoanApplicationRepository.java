package com.finora.loan.repository.application;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
            @Param("closedFunding") Collection<com.finora.loan.domain.application.enums.LoanFundingStatus> closedFunding,
            @Param("closedTerms") Collection<com.finora.loan.domain.application.enums.TermsConfirmationStatus> closedTerms);

    Optional<LoanApplication> findByApplicationNumber(String applicationNumber);

    /** Mã hiển thị {@code HS-...} đã có hồ sơ dùng chưa; bộ sinh mã gọi để chọn lại khi trùng. */
    boolean existsByApplicationCode(String applicationCode);

    Optional<LoanApplication> findByApplicationCode(String applicationCode);

    /**
     * Ô tìm của danh sách hồ sơ quản trị: {@code code} (đã viết hoa, dạng {@code %…%}) khớp mã hồ sơ {@code HS-}/{@code LA-}
     * hoặc mã hợp đồng {@code HĐ-}/{@code LC-} của hồ sơ; {@code borrowerKey} khớp nguyên ID người vay. Họ tên/email người
     * vay nằm ở finora-user nên không tìm được ở đây.
     */
    @Query("""
            select application from LoanApplication application
            where (:status is null or application.status = :status)
              and (:borrowerId is null or application.borrowerId = :borrowerId)
              and (application.applicationCode like :code escape '!'
                   or application.applicationNumber like :code escape '!'
                   or application.borrowerId = :borrowerKey
                   or application.id in (
                       select contract.applicationId from LoanContract contract
                       where contract.contractCode like :code escape '!'
                          or contract.contractNumber like :code escape '!'))
            """)
    Page<LoanApplication> searchAdmin(
            @Param("status") LoanApplicationStatus status,
            @Param("borrowerId") String borrowerId,
            @Param("code") String code,
            @Param("borrowerKey") String borrowerKey,
            Pageable pageable);

    /**
     * Danh sách hồ sơ quản trị lọc theo việc vận hành của khoản vay (đúng một trong ba cờ bật): {@code overdue} là có
     * hồ sơ thu hồi đang mở, {@code reschedule} là có yêu cầu cơ cấu chờ thẩm định, {@code incident} là có sai lệch đối
     * soát đang mở, số liệu lưu đã cũ so với hệ thống lõi hoặc saga giải ngân chờ vận hành. Ô tìm như
     * {@link #searchAdmin}; không tìm thì truyền {@code code = "%"} (khớp mọi hồ sơ vì mã tra cứu luôn có) và
     * {@code borrowerKey} rỗng.
     */
    @Query("""
            select application from LoanApplication application
            where (:status is null or application.status = :status)
              and (:borrowerId is null or application.borrowerId = :borrowerId)
              and (application.applicationCode like :code escape '!'
                   or application.applicationNumber like :code escape '!'
                   or application.borrowerId = :borrowerKey
                   or application.id in (
                       select contract.applicationId from LoanContract contract
                       where contract.contractCode like :code escape '!'
                          or contract.contractNumber like :code escape '!'))
              and ((:overdue = true and application.id in (
                       select loan.loanApplicationId from FinoraLoan loan, LoanCollectionCase collectionCase
                       where collectionCase.finoraLoanId = loan.id
                         and collectionCase.status = com.finora.loan.domain.collection.enums.CollectionCaseStatus.OPEN))
                or (:reschedule = true and application.id in (
                       select loan.loanApplicationId from FinoraLoan loan, LoanRescheduleRequest request
                       where request.finoraLoanId = loan.id
                         and request.status = com.finora.loan.domain.restructure.enums.LoanRescheduleStatus.PENDING_REVIEW))
                or (:incident = true and (
                       application.id in (
                           select loan.loanApplicationId from FinoraLoan loan, LoanReconciliationIncident incident
                           where incident.finoraLoanId = loan.id
                             and incident.status = com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus.OPEN)
                       or application.id in (
                           select loan.loanApplicationId from FinoraLoan loan, LoanServicingProjection projection
                           where projection.finoraLoanId = loan.id and projection.stale = true)
                       or application.id in (
                           select saga.loanApplicationId from DisbursementSaga saga
                           where saga.status = com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus.REPAIR_REQUIRED))))
            """)
    Page<LoanApplication> searchAdminByServicing(
            @Param("status") LoanApplicationStatus status,
            @Param("borrowerId") String borrowerId,
            @Param("code") String code,
            @Param("borrowerKey") String borrowerKey,
            @Param("overdue") boolean overdue,
            @Param("reschedule") boolean reschedule,
            @Param("incident") boolean incident,
            Pageable pageable);

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
            where application.termsConfirmationStatus = com.finora.loan.domain.application.enums.TermsConfirmationStatus.PENDING
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

    /** Mã hồ sơ theo ID — trả cặp {@code [Long id, String applicationNumber]}. */
    @org.springframework.data.jpa.repository.Query(
            "select a.id, a.applicationNumber from LoanApplication a where a.id in :ids")
    java.util.List<Object[]> findApplicationNumbers(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    /** Mã tra cứu và mã hiển thị theo ID — trả bộ {@code [Long id, String applicationNumber, String applicationCode]}. */
    @org.springframework.data.jpa.repository.Query(
            "select a.id, a.applicationNumber, a.applicationCode from LoanApplication a where a.id in :ids")
    java.util.List<Object[]> findApplicationReferences(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    /** Hồ sơ của một người vay theo trạng thái — trang khách hàng và trang thẩm định của admin. */
    Page<LoanApplication> findByBorrowerIdAndStatus(String borrowerId, LoanApplicationStatus status, Pageable pageable);

    /** Worker chỉ lấy ID một batch nhỏ; entity được khóa riêng khi bắt đầu từng bước. */
    @Query("""
            select application.id from LoanApplication application
            where application.status in :statuses
            order by application.updatedAt asc, application.id asc
            """)
    List<Long> findIdsByStatusIn(
            @Param("statuses") Collection<com.finora.loan.domain.application.enums.LoanApplicationStatus> statuses,
            Pageable pageable
    );
}
