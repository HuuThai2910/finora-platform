package com.finora.investment.service.impl;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentLoanServicingStateRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.repository.MarketListingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Phần vốn đang chờ" chỉ gồm phần vốn chưa thành Note: khoản vay đã giải ngân và phát hành Note thì phần vốn đó nằm ở
 * danh mục đầu tư (lỗi gặp khi kiểm thử giao diện ngày 02/11/2026: khoản đã có Note vẫn hiện ở thẻ phần vốn chờ).
 */
class PortfolioServiceImplCommitmentsTest {

    private final InvestmentCommitmentRepository commitments = mock(InvestmentCommitmentRepository.class);
    private final InvestmentNoteRepository notes = mock(InvestmentNoteRepository.class);
    private final InvestmentOrderRepository orders = mock(InvestmentOrderRepository.class);
    private final MarketListingRepository listings = mock(MarketListingRepository.class);
    private final InvestmentMapper mapper = mock(InvestmentMapper.class);
    private final PortfolioServiceImpl service = new PortfolioServiceImpl(commitments, notes, orders, listings,
            mock(InvestmentLoanServicingStateRepository.class), mapper);

    @BeforeEach
    void signIn() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", "13").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commitmentsAlreadyIssuedAsNotesAreNotPending() {
        InvestmentCommitment open = commitment(1L, CommitmentStatus.ACTIVE);
        InvestmentCommitment awaitingNotes = commitment(2L, CommitmentStatus.FINALIZED);
        InvestmentCommitment issued = commitment(3L, CommitmentStatus.FINALIZED);
        when(commitments.findByInvestorIdAndStatusIn(eq("13"), anyList()))
                .thenReturn(List.of(open, awaitingNotes, issued));
        when(notes.existsByCommitmentId(2L)).thenReturn(false);
        when(notes.existsByCommitmentId(3L)).thenReturn(true);

        service.myCommitments();

        verify(mapper).toCommitmentResponse(same(open), any(), any(), any(), anyBoolean());
        verify(mapper).toCommitmentResponse(same(awaitingNotes), any(), any(), any(), anyBoolean());
        verify(mapper, never()).toCommitmentResponse(same(issued), any(), any(), any(), anyBoolean());
    }

    private static InvestmentCommitment commitment(Long id, CommitmentStatus status) {
        return InvestmentCommitment.builder().id(id).orderId(100 + id).listingId(20L).investorId("13").status(status)
                .build();
    }
}
