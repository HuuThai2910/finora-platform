package com.finora.payment.repository.servicing;

import com.finora.payment.domain.servicing.PaymentNoteOwnership;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentNoteOwnershipRepository extends JpaRepository<PaymentNoteOwnership, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select note from PaymentNoteOwnership note where note.noteId = :noteId")
    Optional<PaymentNoteOwnership> findByNoteIdForUpdate(@Param("noteId") Long noteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select note from PaymentNoteOwnership note where note.loanApplicationId = :loanId and note.outstandingPrincipal > 0 order by note.noteId")
    List<PaymentNoteOwnership> findActiveByLoanIdForUpdate(@Param("loanId") Long loanId);
}
