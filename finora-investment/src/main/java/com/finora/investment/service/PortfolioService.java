package com.finora.investment.service;

import com.finora.investment.domain.InvestmentNote;
import com.finora.investment.domain.NoteStatus;
import com.finora.investment.dto.response.PortfolioResponse;
import com.finora.investment.repository.InvestmentNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tổng hợp portfolio investor — tổng đầu tư, phân bổ theo grade.
 */
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final InvestmentNoteRepository noteRepo;

    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(Long investorId) {
        List<InvestmentNote> activeNotes = noteRepo
                .findByInvestorIdAndStatus(investorId, NoteStatus.ACTIVE);

        BigDecimal totalInvested = BigDecimal.ZERO;
        int noteCount = activeNotes.size();
        Map<String, BigDecimal> allocationByGrade = new HashMap<>();

        for (InvestmentNote note : activeNotes) {
            totalInvested = totalInvested.add(note.getPrincipalAmount());
            String grade = note.getListing().getGrade();
            allocationByGrade.merge(grade, note.getPrincipalAmount(), BigDecimal::add);
        }

        return new PortfolioResponse(
                investorId,
                totalInvested,
                noteCount,
                allocationByGrade
        );
    }
}
