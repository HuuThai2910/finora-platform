package com.finora.investment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.note.InvestmentLoanServicingState;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.dto.response.PortfolioPositionResponse;
import com.finora.investment.dto.response.PortfolioResponse;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentLoanServicingStateRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Hồi quy số liệu danh mục (AI-CHAT-001, spec mục 14.2).
 *
 * <p>Lỗi cũ: vốn chờ giải ngân được suy từ "commitment chưa có Note ACTIVE của chính tôi", nên
 * khi Note đã bán hết trên chợ, đã CLOSED hoặc chuyển DEFAULTED thì số vốn đã giải ngân từ lâu
 * lại hiện thành "chờ giải ngân". Note DEFAULTED cũng biến mất khỏi danh mục. Repository là mock:
 * test kiểm cách service ghép số, còn câu truy vấn được kiểm khi chạy thật. Danh mục theo R3-W2: giữ
 * vị thế CLOSED/DEFAULTED, vốn đang đầu tư gồm cả dư nợ nợ xấu (FundingFlowIT kiểm phần CLOSED với DB).</p>
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceImplTest {

    private static final String INVESTOR = "7";
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");
    private static final List<CommitmentStatus> LIVE_COMMITMENTS =
            List.of(CommitmentStatus.ACTIVE, CommitmentStatus.FINALIZED);
    private static final List<NoteStatus> PORTFOLIO_NOTES =
            List.of(NoteStatus.ACTIVE, NoteStatus.DEFAULTED, NoteStatus.CLOSED);

    @Mock
    private InvestmentCommitmentRepository commitmentRepository;
    @Mock
    private InvestmentNoteRepository noteRepository;
    @Mock
    private MarketListingRepository listingRepository;
    @Mock
    private InvestmentLoanServicingStateRepository servicingStateRepository;
    @Mock
    private InvestmentMapper mapper;

    private PortfolioServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PortfolioServiceImpl(commitmentRepository, noteRepository, listingRepository,
                servicingStateRepository, mapper);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", INVESTOR)
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(900)).build();
        // Có authority thì token mới ở trạng thái "đã xác thực" mà SecurityUtils đòi hỏi.
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_INVESTOR"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chuaGiaiNganThiVonChoBangTongCommitmentChuaCoNote() {
        // Hai phần vốn chưa giải ngân, cộng một Note mua lại trên chợ từ commitment 99 của người khác:
        // Note mua lại nằm trong danh mục nhưng không làm đổi vốn chờ của chính nhà đầu tư.
        when(commitmentRepository.findByInvestorIdAndStatusIn(INVESTOR, LIVE_COMMITMENTS)).thenReturn(List.of(
                commitment(11, CommitmentStatus.ACTIVE, "2000000.00"),
                commitment(12, CommitmentStatus.FINALIZED, "3000000.00")));
        when(noteRepository.findByInvestorIdAndStatusIn(INVESTOR, PORTFOLIO_NOTES)).thenReturn(List.of(
                note(1, 99, 101, NoteStatus.ACTIVE, "1000000.00", "10000.00", "14.0000")));
        when(noteRepository.findCommitmentIdsWithNotes(List.of(11L, 12L))).thenReturn(List.of());

        PortfolioResponse portfolio = service.portfolio();

        assertThat(portfolio.pendingAmount()).isEqualTo("5000000.00");
        assertThat(portfolio.investedAmount()).isEqualTo("1000000.00");
        assertThat(portfolio.positions()).extracting(PortfolioPositionResponse::loanId).containsExactly(101L);
        // Một truy vấn cho cả danh mục, không hỏi từng commitment một (tránh N+1).
        verify(noteRepository).findCommitmentIdsWithNotes(anyList());
        verify(noteRepository, never()).existsByCommitmentId(any());
        verify(noteRepository, never()).findByCommitmentId(any());
    }

    @Test
    void banHetNoteTrenChoThiVonChoBangKhong() {
        // Note của commitment 21 đã sang tay người mua: nhà đầu tư không còn Note nào, nhưng
        // commitment vẫn FINALIZED. Cách cũ cộng lại 2 triệu này thành "chờ giải ngân".
        when(commitmentRepository.findByInvestorIdAndStatusIn(INVESTOR, LIVE_COMMITMENTS)).thenReturn(List.of(
                commitment(21, CommitmentStatus.FINALIZED, "2000000.00")));
        when(noteRepository.findByInvestorIdAndStatusIn(INVESTOR, PORTFOLIO_NOTES)).thenReturn(List.of());
        when(noteRepository.findCommitmentIdsWithNotes(List.of(21L))).thenReturn(List.of(21L));

        PortfolioResponse portfolio = service.portfolio();

        assertThat(portfolio.pendingAmount()).isEqualTo("0.00");
        assertThat(portfolio.investedAmount()).isEqualTo("0.00");
        assertThat(portfolio.positions()).isEmpty();
        assertThat(portfolio.positionCount()).isZero();
    }

    @Test
    void noteDaDongThiVonChoBangKhongVaViTheGiuNhanDaTatToan() {
        // Commitment 31 có Note đã CLOSED (thu hồi đủ gốc), commitment 32 còn Note ACTIVE. Vị thế CLOSED vẫn
        // nằm trong danh mục để giữ lịch sử gốc/lãi (R3-W2, B16) nhưng không thành "chờ giải ngân".
        when(commitmentRepository.findByInvestorIdAndStatusIn(INVESTOR, LIVE_COMMITMENTS)).thenReturn(List.of(
                commitment(31, CommitmentStatus.FINALIZED, "1000000.00"),
                commitment(32, CommitmentStatus.FINALIZED, "1000000.00")));
        when(noteRepository.findByInvestorIdAndStatusIn(INVESTOR, PORTFOLIO_NOTES)).thenReturn(List.of(
                note(1, 31, 301, NoteStatus.CLOSED, "0.00", "90000.00", "14.0000"),
                note(2, 32, 302, NoteStatus.ACTIVE, "600000.00", "30000.00", "12.0000")));
        when(noteRepository.findCommitmentIdsWithNotes(List.of(31L, 32L))).thenReturn(List.of(31L, 32L));

        PortfolioResponse portfolio = service.portfolio();

        assertThat(portfolio.pendingAmount()).isEqualTo("0.00");
        assertThat(portfolio.investedAmount()).isEqualTo("600000.00");
        assertThat(portfolio.positions()).extracting(PortfolioPositionResponse::loanId).containsExactly(301L, 302L);
        assertThat(portfolio.positions()).extracting(PortfolioPositionResponse::status)
                .containsExactly("CLOSED", "ACTIVE");
        assertThat(portfolio.activeNoteCount()).isEqualTo(1);
        // Lãi suất bình quân chỉ tính phần còn sinh lãi.
        assertThat(portfolio.weightedAverageRate()).isEqualByComparingTo("12.0000");
        assertThat(portfolio.defaultedPositionCount()).isZero();
    }

    @Test
    void noteVoNoHienTrongDanhMucNeuRiengPhanNoXauVaKhongTinhVaoVonCho() {
        when(commitmentRepository.findByInvestorIdAndStatusIn(INVESTOR, LIVE_COMMITMENTS)).thenReturn(List.of(
                commitment(41, CommitmentStatus.FINALIZED, "2000000.00"),
                commitment(42, CommitmentStatus.FINALIZED, "1000000.00")));
        when(noteRepository.findByInvestorIdAndStatusIn(INVESTOR, PORTFOLIO_NOTES)).thenReturn(List.of(
                note(3, 41, 401, NoteStatus.DEFAULTED, "800000.00", "50000.00", "15.0000"),
                note(4, 41, 401, NoteStatus.DEFAULTED, "800000.00", "50000.00", "15.0000"),
                note(5, 42, 402, NoteStatus.ACTIVE, "1000000.00", "20000.00", "12.0000")));
        when(noteRepository.findCommitmentIdsWithNotes(List.of(41L, 42L))).thenReturn(List.of(41L, 42L));
        when(servicingStateRepository.findByLoanApplicationIdIn(anyList())).thenReturn(List.of(
                InvestmentLoanServicingState.delinquency(401L, "LN-401", 120, 3, new BigDecimal("300000"),
                        LocalDate.parse("2026-06-17"), new BigDecimal("1600000"), NOW, NOW, NOW)));

        PortfolioResponse portfolio = service.portfolio();

        assertThat(portfolio.pendingAmount()).isEqualTo("0.00");
        // Vốn đang đầu tư gồm cả dư nợ của khoản nợ xấu (tiền chưa thu hồi, R3-W2); phần nợ xấu nêu riêng ở
        // defaultedAmount và không kéo lãi suất bình quân.
        assertThat(portfolio.investedAmount()).isEqualTo("2600000.00");
        assertThat(portfolio.defaultedAmount()).isEqualTo("1600000.00");
        assertThat(portfolio.weightedAverageRate()).isEqualByComparingTo("12.0000");
        // Tiền đã nhận là tiền thật đã về ví nên cộng trên mọi vị thế, kể cả vị thế nợ xấu.
        assertThat(portfolio.principalRepaid()).isEqualTo("400000.00");
        assertThat(portfolio.interestReceived()).isEqualTo("120000.00");
        assertThat(portfolio.totalReceived()).isEqualTo("520000.00");
        assertThat(portfolio.activeNoteCount()).isEqualTo(1);
        // Số vị thế đếm mọi khoản trong danh mục, kể cả khoản nợ xấu.
        assertThat(portfolio.positionCount()).isEqualTo(2);
        assertThat(portfolio.defaultedNoteCount()).isEqualTo(2);
        assertThat(portfolio.defaultedPositionCount()).isEqualTo(1);

        assertThat(portfolio.positions()).extracting(PortfolioPositionResponse::loanId).containsExactly(401L, 402L);
        PortfolioPositionResponse bad = portfolio.positions().get(0);
        assertThat(bad.status()).isEqualTo("DEFAULTED");
        assertThat(bad.noteCount()).isEqualTo(2);
        assertThat(bad.outstandingPrincipal()).isEqualTo("1600000.00");
        assertThat(bad.daysPastDue()).isEqualTo(120);
        assertThat(bad.debtGroup()).isEqualTo(3);
        assertThat(portfolio.positions().get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void chuaCamKetGiThiKhongGuiTruyVanInRong() {
        when(commitmentRepository.findByInvestorIdAndStatusIn(INVESTOR, LIVE_COMMITMENTS)).thenReturn(List.of());
        when(noteRepository.findByInvestorIdAndStatusIn(INVESTOR, PORTFOLIO_NOTES)).thenReturn(List.of());

        PortfolioResponse portfolio = service.portfolio();

        assertThat(portfolio.pendingAmount()).isEqualTo("0.00");
        assertThat(portfolio.defaultedAmount()).isEqualTo("0.00");
        assertThat(portfolio.positions()).isEmpty();
        verify(noteRepository, never()).findCommitmentIdsWithNotes(anyList());
    }

    private static InvestmentCommitment commitment(long id, CommitmentStatus status, String amount) {
        return InvestmentCommitment.builder()
                .id(id)
                .orderId(1000 + id)
                .listingId(id)
                .investorId(INVESTOR)
                .amount(new BigDecimal(amount))
                .status(status)
                .build();
    }

    /**
     * Note mệnh giá 1.000.000 đ; gốc đã thu = mệnh giá trừ dư nợ. Đợt gọi vốn lấy cùng số với
     * khoản vay vì test không cần phân biệt hai mã này.
     */
    private static InvestmentNote note(long id, long commitmentId, long loanId, NoteStatus status,
            String outstanding, String interest, String rate) {
        BigDecimal principal = new BigDecimal("1000000.00");
        return InvestmentNote.builder()
                .id(id)
                .commitmentId(commitmentId)
                .listingId(loanId)
                .loanId(loanId)
                .investorId(INVESTOR)
                .principalAmount(principal)
                .outstandingPrincipal(new BigDecimal(outstanding))
                .principalRepaid(principal.subtract(new BigDecimal(outstanding)))
                .interestReceived(new BigDecimal(interest))
                .annualInterestRate(new BigDecimal(rate))
                .termMonths(12)
                .status(status)
                .build();
    }
}
