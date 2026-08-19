package com.finora.investment.repository;

import com.finora.investment.domain.InvestmentNote;
import com.finora.investment.domain.NoteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestmentNoteRepository extends JpaRepository<InvestmentNote, Long> {

    Page<InvestmentNote> findByInvestorId(Long investorId, Pageable pageable);

    List<InvestmentNote> findByInvestorIdAndStatus(Long investorId, NoteStatus status);

    List<InvestmentNote> findByListingId(Long listingId);
}
