package com.finora.investment.service;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.investment.domain.InvestmentNote;
import com.finora.investment.domain.MatchResult;
import com.finora.investment.repository.InvestmentNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý Investment Notes — khoản đầu tư fractional.
 * Tạo Note tự động từ MatchResult.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NoteService {

    private final InvestmentNoteRepository noteRepo;

    /**
     * Tạo Note từ kết quả khớp lệnh.
     */
    @Transactional
    public InvestmentNote createFromMatch(MatchResult match) {
        var note = InvestmentNote.builder()
                .listing(match.getListing())
                .match(match)
                .investorId(match.getOrder().getInvestorId())
                .principalAmount(match.getMatchedAmount())
                .build();

        note = noteRepo.save(note);
        log.info("Tạo Note: id={} investor={} amount={} listing={}",
                note.getId(), note.getInvestorId(),
                note.getPrincipalAmount(), match.getListing().getId());
        return note;
    }

    @Transactional(readOnly = true)
    public Page<InvestmentNote> findByInvestor(Long investorId, Pageable pageable) {
        return noteRepo.findByInvestorId(investorId, pageable);
    }

    @Transactional(readOnly = true)
    public InvestmentNote findById(Long id) {
        return noteRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Note", "id", id));
    }
}
