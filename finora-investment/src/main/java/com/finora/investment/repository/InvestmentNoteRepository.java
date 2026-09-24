package com.finora.investment.repository;

import com.finora.investment.domain.note.InvestmentNote;
import com.finora.common.enums.investment.NoteStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentNoteRepository extends JpaRepository<InvestmentNote, Long> {

    List<InvestmentNote> findByCommitmentId(Long commitmentId);

    boolean existsByCommitmentId(Long commitmentId);

    Page<InvestmentNote> findByInvestorIdOrderByIssuedAtDesc(String investorId, Pageable pageable);

    List<InvestmentNote> findByInvestorIdAndStatus(String investorId, NoteStatus status);

    List<InvestmentNote> findByLoanIdAndStatus(Long loanId, NoteStatus status);
}
