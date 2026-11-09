package com.finora.loan.repository.restructure;

import com.finora.loan.domain.restructure.entity.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
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
import java.util.UUID;

public interface LoanRescheduleRequestRepository extends JpaRepository<LoanRescheduleRequest, Long> {
    Optional<LoanRescheduleRequest> findByRequestId(UUID requestId);

    /** Lịch sử xử lý hồ sơ: mọi yêu cầu cơ cấu/gia hạn của một khoản vay, cũ trước. */
    List<LoanRescheduleRequest> findByFinoraLoanIdOrderByCreatedAtAsc(Long finoraLoanId);
    Optional<LoanRescheduleRequest> findByBorrowerIdAndIdempotencyKey(String borrowerId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from LoanRescheduleRequest request where request.requestId = :requestId")
    Optional<LoanRescheduleRequest> findByRequestIdForUpdate(@Param("requestId") UUID requestId);

    Page<LoanRescheduleRequest> findByBorrowerIdAndLoanNumberOrderByCreatedAtDesc(
            String borrowerId, String loanNumber, Pageable pageable);
    Page<LoanRescheduleRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Yêu cầu cơ cấu của một khoản vay (phần "Sau giải ngân" của trang hồ sơ), mới nhất trước. */
    Page<LoanRescheduleRequest> findByLoanNumberOrderByCreatedAtDesc(String loanNumber, Pageable pageable);
    Page<LoanRescheduleRequest> findByLoanNumberAndStatusOrderByCreatedAtDesc(
            String loanNumber, LoanRescheduleStatus status, Pageable pageable);

    /** Khoản vay (trong {@code loanIds}) có yêu cầu cơ cấu ở trạng thái {@code status}: nhãn trên danh sách hồ sơ. */
    @Query("select distinct request.finoraLoanId from LoanRescheduleRequest request "
            + "where request.status = :status and request.finoraLoanId in :loanIds")
    List<Long> findLoanIdsWithStatus(@Param("status") LoanRescheduleStatus status,
            @Param("loanIds") Collection<Long> loanIds);
    Page<LoanRescheduleRequest> findByStatusOrderByCreatedAtDesc(LoanRescheduleStatus status, Pageable pageable);

    @Query("""
            select request.id from LoanRescheduleRequest request
            where request.status in (:readyStatuses)
                  and (request.nextAttemptAt is null or request.nextAttemptAt <= :now)
               or request.status in (:processingStatuses)
                  and request.processingStartedAt <= :leaseExpiredBefore
            order by request.updatedAt, request.id
            """)
    List<Long> findDueIds(
            @Param("readyStatuses") List<LoanRescheduleStatus> readyStatuses,
            @Param("processingStatuses") List<LoanRescheduleStatus> processingStatuses,
            @Param("now") Instant now,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from LoanRescheduleRequest request where request.id = :id")
    Optional<LoanRescheduleRequest> findByIdForUpdate(@Param("id") Long id);

    /**
     * Ô tìm của hàng đợi cơ cấu: như tìm hồ sơ thu hồi, khớp mã khoản vay {@code LN-}, mã hồ sơ hoặc mã hợp đồng;
     * {@code status} {@code null} là mọi trạng thái. Mới nhất trước.
     */
    @Query("""
            select request from LoanRescheduleRequest request
            where (:status is null or request.status = :status)
              and (request.loanNumber like :code escape '!'
                   or request.finoraLoanId in (
                       select loan.id from FinoraLoan loan
                       where loan.applicationNumber like :code escape '!'
                          or loan.contractNumber like :code escape '!'
                          or loan.loanApplicationId in (
                              select application.id from LoanApplication application
                              where application.applicationCode like :code escape '!')
                          or loan.contractNumber in (
                              select contract.contractNumber from LoanContract contract
                              where contract.contractCode like :code escape '!')))
            order by request.createdAt desc
            """)
    Page<LoanRescheduleRequest> search(
            @Param("status") LoanRescheduleStatus status,
            @Param("code") String code,
            Pageable pageable);
}
