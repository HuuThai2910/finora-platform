package com.finora.investment.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.investment.domain.note.entity.InvestmentNote;
import com.finora.investment.dto.response.AdminInvestorNoteResponse;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.service.AdminInvestorPortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminInvestorPortfolioServiceImpl implements AdminInvestorPortfolioService {

    private static final int MAX_PAGE_SIZE = 100;

    private final InvestmentNoteRepository investmentNoteRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminInvestorNoteResponse> notesOf(String investorId, int page, int size) {
        Page<InvestmentNote> notes = investmentNoteRepository.findByInvestorIdOrderByIssuedAtDesc(
                investorId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
        return PageResponse.<AdminInvestorNoteResponse>builder()
                .content(notes.getContent().stream().map(AdminInvestorPortfolioServiceImpl::toResponse).toList())
                .page(notes.getNumber())
                .size(notes.getSize())
                .totalElements(notes.getTotalElements())
                .totalPages(notes.getTotalPages())
                .last(notes.isLast())
                .build();
    }

    private static AdminInvestorNoteResponse toResponse(InvestmentNote note) {
        return new AdminInvestorNoteResponse(
                note.getNoteNumber(), note.getListingId(), note.getLoanId(),
                note.getPrincipalAmount().toPlainString(), note.getOutstandingPrincipal().toPlainString(),
                note.getInterestReceived().toPlainString(), note.getAnnualInterestRate().toPlainString(),
                note.getTermMonths(), note.getStatus().name(), note.getIssuedAt());
    }
}
