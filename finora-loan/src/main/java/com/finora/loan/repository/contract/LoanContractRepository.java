package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
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

public interface LoanContractRepository extends JpaRepository<LoanContract, Long> {

    Optional<LoanContract> findByContractNumber(String contractNumber);

    /** Mã hiển thị {@code HĐ-...} đã có hợp đồng dùng chưa; bộ sinh mã gọi để chọn lại khi trùng. */
    boolean existsByContractCode(String contractCode);

    Optional<LoanContract> findByApplicationId(Long applicationId);

    /**
     * Chỉ lấy số hợp đồng của hồ sơ cho màn duyệt (B73): một query theo khóa duy nhất {@code application_id},
     * không tải nội dung văn bản hợp đồng. Rỗng khi Contract chưa được lập.
     */
    @Query("select contract.contractNumber from LoanContract contract where contract.applicationId = :applicationId")
    Optional<String> findContractNumberByApplicationId(@Param("applicationId") Long applicationId);

    /** Mã hiển thị {@code HĐ-...} theo mã tra cứu, không tải nội dung văn bản; dùng cho danh sách cần hiện mã. */
    @Query("select contract.contractNumber as contractNumber, contract.contractCode as contractCode "
            + "from LoanContract contract where contract.contractNumber in :contractNumbers")
    List<ContractCodeView> findContractCodes(@Param("contractNumbers") Collection<String> contractNumbers);

    /** Hồ sơ của Contract theo mã hiển thị, không tải nội dung văn bản; dùng cho ô "Mở hồ sơ theo mã" của web. */
    @Query("select contract.applicationId from LoanContract contract where contract.contractCode = :contractCode")
    Optional<Long> findApplicationIdByContractCode(@Param("contractCode") String contractCode);

    /** Hồ sơ của Contract theo mã tra cứu {@code LC-...}, không tải nội dung văn bản. */
    @Query("select contract.applicationId from LoanContract contract where contract.contractNumber = :contractNumber")
    Optional<Long> findApplicationIdByContractNumber(@Param("contractNumber") String contractNumber);

    /**
     * Mã tra cứu và mã hiển thị của Contract thuộc hồ sơ trong một lần đọc, không tải nội dung văn bản. Rỗng khi
     * Contract chưa được lập.
     */
    @Query("select contract.contractNumber as contractNumber, contract.contractCode as contractCode "
            + "from LoanContract contract where contract.applicationId = :applicationId")
    Optional<ContractCodeView> findContractReferenceByApplicationId(@Param("applicationId") Long applicationId);

    Optional<LoanContract> findByConsentIdempotencyKey(String consentIdempotencyKey);

    Page<LoanContract> findByBorrowerId(String borrowerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select contract from LoanContract contract where contract.contractNumber = :contractNumber")
    Optional<LoanContract> findByContractNumberForUpdate(@Param("contractNumber") String contractNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select contract from LoanContract contract where contract.id = :id")
    Optional<LoanContract> findByIdForUpdate(@Param("id") Long id);

    /** Worker chỉ lấy ID đến hạn theo partial index rồi khóa từng Contract trong transaction riêng. */
    @Query("""
            select contract.id from LoanContract contract
            where contract.status in :statuses and contract.expiresAt <= :now
            order by contract.expiresAt asc, contract.id asc
            """)
    List<Long> findDueIds(
            @Param("statuses") List<LoanContractStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    /** Cặp mã tra cứu và mã hiển thị của một Contract. */
    interface ContractCodeView {

        String getContractNumber();

        String getContractCode();
    }
}
