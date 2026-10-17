package com.finora.notification.repository;

import com.finora.notification.domain.LoanReference;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanReferenceRepository extends JpaRepository<LoanReference, Long> {

    /** Hai listener (khác topic) cùng cập nhật một khoản vay: khóa dòng để không ghi đè lẫn nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LoanReference r where r.loanApplicationId = :id")
    Optional<LoanReference> findForUpdate(@Param("id") Long loanApplicationId);
}
