package com.finora.loan.repository.collection;

import com.finora.loan.domain.collection.entity.*;
import com.finora.loan.domain.collection.enums.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanCollectionCaseRepository extends JpaRepository<LoanCollectionCase, Long> {
    Optional<LoanCollectionCase> findByCaseId(UUID caseId);

    /** Lịch sử xử lý hồ sơ: các đợt thu hồi nợ của một khoản vay, cũ trước. */
    List<LoanCollectionCase> findByFinoraLoanIdOrderByOpenedAtAsc(Long finoraLoanId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanCollectionCase value where value.caseId = :caseId")
    Optional<LoanCollectionCase> findByCaseIdForUpdate(@Param("caseId") UUID caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from LoanCollectionCase value where value.finoraLoanId = :loanId and value.status = :status")
    Optional<LoanCollectionCase> findByLoanIdAndStatusForUpdate(
            @Param("loanId") Long loanId, @Param("status") CollectionCaseStatus status);

    Page<LoanCollectionCase> findAllByOrderByDaysPastDueDescUpdatedAtAsc(Pageable pageable);

    /** Hồ sơ thu hồi của một khoản vay (phần "Sau giải ngân" của trang hồ sơ), mới mở trước. */
    Page<LoanCollectionCase> findByLoanNumberOrderByOpenedAtDesc(String loanNumber, Pageable pageable);
    Page<LoanCollectionCase> findByLoanNumberAndStatusOrderByOpenedAtDesc(
            String loanNumber, CollectionCaseStatus status, Pageable pageable);

    /** Khoản vay (trong {@code loanIds}) có hồ sơ thu hồi ở trạng thái {@code status}: nhãn trên danh sách hồ sơ. */
    @Query("select distinct value.finoraLoanId from LoanCollectionCase value "
            + "where value.status = :status and value.finoraLoanId in :loanIds")
    List<Long> findLoanIdsWithStatus(@Param("status") CollectionCaseStatus status,
            @Param("loanIds") Collection<Long> loanIds);
    Page<LoanCollectionCase> findByStatusOrderByDaysPastDueDescUpdatedAtAsc(
            CollectionCaseStatus status, Pageable pageable);
    Page<LoanCollectionCase> findByStageOrderByDaysPastDueDescUpdatedAtAsc(
            CollectionStage stage, Pageable pageable);
    Page<LoanCollectionCase> findByStatusAndStageOrderByDaysPastDueDescUpdatedAtAsc(
            CollectionCaseStatus status, CollectionStage stage, Pageable pageable);

    /**
     * Ô tìm của hàng đợi thu hồi: {@code code} (đã viết hoa, dạng {@code %…%}) khớp mã khoản vay {@code LN-}, mã hồ sơ
     * {@code HS-}/{@code LA-} hoặc mã hợp đồng {@code HĐ-}/{@code LC-} của khoản vay; trạng thái/giai đoạn {@code null} là
     * không lọc. Cùng thứ tự với danh sách không tìm: DPD cao trước.
     */
    @Query("""
            select collectionCase from LoanCollectionCase collectionCase
            where (:status is null or collectionCase.status = :status)
              and (:stage is null or collectionCase.stage = :stage)
              and (collectionCase.loanNumber like :code escape '!'
                   or collectionCase.finoraLoanId in (
                       select loan.id from FinoraLoan loan
                       where loan.applicationNumber like :code escape '!'
                          or loan.contractNumber like :code escape '!'
                          or loan.loanApplicationId in (
                              select application.id from LoanApplication application
                              where application.applicationCode like :code escape '!')
                          or loan.contractNumber in (
                              select contract.contractNumber from LoanContract contract
                              where contract.contractCode like :code escape '!')))
            order by collectionCase.daysPastDue desc, collectionCase.updatedAt asc
            """)
    Page<LoanCollectionCase> search(
            @Param("status") CollectionCaseStatus status,
            @Param("stage") CollectionStage stage,
            @Param("code") String code,
            Pageable pageable);
}
