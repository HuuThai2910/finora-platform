package com.finora.loan.service.restructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.config.LoanRescheduleProperties;
import com.finora.loan.domain.restructure.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import com.finora.loan.domain.restructure.LoanRescheduleType;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import com.finora.loan.dto.restructure.request.AdminLoanRescheduleDecisionRequest;
import com.finora.loan.integration.fineract.client.FineractRescheduleGateway;
import com.finora.loan.integration.fineract.client.FineractServicingGateway;
import com.finora.loan.messaging.event.LoanRescheduleConsentRequestedEventData;
import com.finora.loan.messaging.event.LoanRescheduledEventData;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.collection.CollectionCaseService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Field bổ sung của hai event cơ cấu (W3F): {@code borrowerId} trong {@code LoanRescheduleConsentRequested.v1} (P2) và kỳ
 * tiếp theo của lịch mới trong {@code LoanRescheduled.v1} (P3), lấy từ đúng snapshot Fineract đọc sau khi duyệt lịch.
 */
class LoanRescheduleProducerFieldsTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long FINORA_LOAN_ID = 10L;
    private static final Long FINERACT_LOAN_ID = 88L;
    private static final Long CORE_RESCHEDULE_ID = 55L;

    private final LoanRescheduleRequestRepository requests = mock(LoanRescheduleRequestRepository.class);
    private final FinoraLoanRepository loans = mock(FinoraLoanRepository.class);
    private final LoanServicingProjectionRepository projections = mock(LoanServicingProjectionRepository.class);
    private final FineractRescheduleGateway rescheduleGateway = mock(FineractRescheduleGateway.class);
    private final FineractServicingGateway servicingGateway = mock(FineractServicingGateway.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final CollectionCaseService collectionCases = mock(CollectionCaseService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final LoanRescheduleProperties properties = new LoanRescheduleProperties(
            true, 5_000, 20, 10, null, null, null, 7L, null, null);
    private FinoraLoan loan;
    private LoanRescheduleRequest request;

    @BeforeEach
    void setUp() {
        loan = FinoraLoan.activate("LN-10", 99L, "LA-10", "LC-10", "borrower-42", FINERACT_LOAN_ID,
                new BigDecimal("10000000.00"), "VND", NOW.minus(Duration.ofDays(40)));
        ReflectionTestUtils.setField(loan, "id", FINORA_LOAN_ID);
        request = LoanRescheduleRequest.submit(REQUEST_ID, FINORA_LOAN_ID, "LN-10", "borrower-42",
                LoanRescheduleType.TERM_EXTENSION, LocalDate.of(2026, 11, 1), null, 2, "Giảm thu nhập",
                "LOAN_RESTRUCTURE_TERMS_V1", "a".repeat(64), "submit-key", "b".repeat(64),
                LocalDate.of(2027, 8, 1), NOW.minusSeconds(600));
        when(requests.findByRequestIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(1L)).thenReturn(Optional.of(request));
        when(loans.findById(FINORA_LOAN_ID)).thenReturn(Optional.of(loan));
        when(loans.findByIdForUpdate(FINORA_LOAN_ID)).thenReturn(Optional.of(loan));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void consentRequestCarriesTheBorrowerOfTheLoan() {
        actAsAdmin();
        LoanRescheduleService service = new LoanRescheduleService(requests, loans, projections, properties,
                mock(HashingService.class), outbox, clock);

        service.approve(REQUEST_ID, "approve-key", new AdminLoanRescheduleDecisionRequest("Đủ điều kiện"));

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(outbox).recordForPublication(eq("LoanRescheduleRequest"), eq(REQUEST_ID.toString()),
                eq("LoanRescheduleConsentRequested"), eq(1), data.capture());
        LoanRescheduleConsentRequestedEventData event = (LoanRescheduleConsentRequestedEventData) data.getValue();
        assertThat(event.borrowerId()).isEqualTo("borrower-42");
        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
        assertThat(request.getStatus()).isEqualTo(LoanRescheduleStatus.PENDING_INVESTOR_CONSENT);
    }

    @Test
    void rescheduledEventCarriesTheNextInstallmentOfTheApprovedSchedule() {
        LoanRescheduledEventData event = executeApproval(LocalDate.of(2026, 12, 1), new BigDecimal("1234567.00"));

        assertThat(event.newMaturityDate()).isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(event.nextDueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(event.nextDueAmount()).isEqualTo("1234567.00");
        assertThat(event.borrowerId()).isEqualTo("borrower-42");
        assertThat(request.getStatus()).isEqualTo(LoanRescheduleStatus.COMPLETED);
    }

    @Test
    void snapshotWithoutNextInstallmentLeavesTheFieldsEmpty() {
        LoanRescheduledEventData event = executeApproval(null, null);

        assertThat(event.nextDueDate()).isNull();
        assertThat(event.nextDueAmount()).isNull();
    }

    private LoanRescheduledEventData executeApproval(LocalDate nextDueDate, BigDecimal nextDueAmount) {
        request.approveForInvestorConsent("admin-1", "Đủ điều kiện", "approve-key", NOW.minusSeconds(500));
        request.investorsApproved(NOW.minusSeconds(400));
        request.claim(NOW.minusSeconds(300), Duration.ofMinutes(2));
        request.coreCreated(CORE_RESCHEDULE_ID, NOW.minusSeconds(200));
        loan.beginRestructuring(NOW.minusSeconds(400));
        LoanServicingProjection projection = LoanServicingProjection.fromContractSnapshot(FINORA_LOAN_ID,
                new BigDecimal("10000000.00"), new BigDecimal("900000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10900000.00"), LocalDate.of(2026, 11, 1), new BigDecimal("1816667.00"),
                LocalDate.of(2027, 8, 1), NOW.minus(Duration.ofDays(40)));
        when(projections.findByFinoraLoanIdForUpdate(FINORA_LOAN_ID)).thenReturn(Optional.of(projection));
        when(rescheduleGateway.read(CORE_RESCHEDULE_ID))
                .thenReturn(new FineractRescheduleGateway.CoreReschedule(CORE_RESCHEDULE_ID, "loanStatusType.approved"));
        when(servicingGateway.read(eq(FINERACT_LOAN_ID), any())).thenReturn(new LoanServicingSnapshot(
                FINERACT_LOAN_ID, "LC-10", "loanStatusType.active", new BigDecimal("10000000.00"),
                BigDecimal.ZERO, new BigDecimal("10000000.00"), new BigDecimal("1100000.00"), BigDecimal.ZERO,
                new BigDecimal("1100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("11100000.00"),
                BigDecimal.ZERO, null, 0, nextDueDate, nextDueAmount, LocalDate.of(2027, 10, 1)));
        when(collectionCases.apply(any(), any(), anyInt(), any()))
                .thenReturn(new CollectionCaseService.DebtGroupChange(1, 1));
        LoanRescheduleExecutionService service = new LoanRescheduleExecutionService(requests, loans, projections,
                rescheduleGateway, servicingGateway, properties, outbox, collectionCases, clock, inlineTransactions());

        service.execute(1L);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(outbox).recordForPublication(eq("FinoraLoan"), eq(FINORA_LOAN_ID.toString()), eq("LoanRescheduled"),
                eq(1), data.capture());
        return (LoanRescheduledEventData) data.getValue();
    }

    /** Chạy callback ngay trên luồng gọi; {@code executeWithoutResult} mặc định cũng đi qua {@code execute}. */
    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private static void actAsAdmin() {
        Jwt jwt = Jwt.withTokenValue("admin-token").header("alg", "RS256").subject("admin-1")
                .claim("user_id", "admin-1").issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "admin-1"));
    }
}
