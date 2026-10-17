package com.finora.loan;

import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.PinTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.config.AiCreditProperties;
import com.finora.loan.domain.scoring.AiRecommendation;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import com.finora.loan.integration.ai.contract.AiCreditScoreResponse;
import com.finora.loan.integration.ai.contract.AiCreditScoreRequest;
import com.finora.loan.integration.ai.client.AiCreditScoringGateway;
import com.finora.loan.integration.ai.client.AiCreditScoringHttpClient;
import com.finora.loan.integration.fineract.client.FineractLoanProductGateway;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.integration.fineract.client.FineractScheduleGateway;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import com.finora.loan.integration.fineract.contract.SchedulePeriod;
import com.finora.loan.integration.profile.provider.BorrowerProfileProvider;
import com.finora.loan.integration.profile.provider.BorrowerProfileUnavailableException;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.messaging.event.FundingAllocationEventData;
import com.finora.loan.messaging.event.LoanFullyFundedEventData;
import com.finora.loan.service.scoring.CreditScoringOrchestrator;
import com.finora.loan.service.application.LoanTermsConfirmationExpiryStateService;
import com.finora.loan.service.funding.LoanFullyFundedHandler;
import com.finora.loan.support.HashingService;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "finora.ai.credit.worker-enabled=false",
        "finora.loan.contract.expiry-worker-enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "finora.loan.outbox.publisher-delay=3600000",
        "finora.signature.provider=mock",
        // Worker đối soát SmartCA được gọi tay trong test; không để lịch chạy chen giữa các bước.
        "finora.signature.pades.worker-delay=3600000",
        // Hạn mức dư nợ là cấu hình; IT dùng 150 triệu vì các test N+1 nộp ba hồ sơ 50 triệu cho cùng người vay.
        "finora.loan.borrower-exposure.max-outstanding=150000000"
})
// Tắt security filter: test này kiểm thử nghiệp vụ vay, còn danh tính người gọi đã
// được thay bằng mock CurrentUserProvider. Phân quyền có test riêng ở tầng đơn vị.
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
class FinoraLoanApplicationIT {

    /** Ngày cố định đủ xa để test @FutureOrPresent không phụ thuộc ngày chạy CI. */
    private static final LocalDate EXPECTED_DISBURSEMENT_DATE = LocalDate.of(2099, 8, 10);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_loan_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired Flyway flyway;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired CreditScoringOrchestrator scoringOrchestrator;
    @Autowired LoanTermsConfirmationExpiryStateService termsExpiryStateService;
    @Autowired LoanFullyFundedHandler fullyFundedHandler;
    @Autowired com.finora.loan.service.contract.LoanContractStateService contractStateService;
    @Autowired PinTokenService pinTokenService;
    @Autowired HashingService hashingService;
    @Autowired com.finora.loan.service.contract.pades.PadesSignatureVerifier padesVerifier;
    @Autowired AiCreditProperties aiCreditProperties;
    @Autowired CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @Autowired @Qualifier("aiCreditRestClient") RestClient aiCreditRestClient;

    @MockBean FineractLoanProductGateway productGateway;
    @MockBean FineractScheduleGateway scheduleGateway;
    @MockBean BorrowerProfileProvider borrowerProfileProvider;
    @MockBean AiCreditScoringGateway aiCreditScoringGateway;
    /** Mặc định là MockSignatureProvider thật; chỉ test ký bất đồng bộ mới stub để giả lập SmartCA. */
    @SpyBean com.finora.loan.integration.signature.SignatureProvider signatureProvider;
    @Autowired com.finora.loan.service.contract.ContractPdfSignatureWorker pdfSignatureWorker;

    private final AtomicLong fineractIds = new AtomicLong(1000);

    @BeforeEach
    void configureDeterministicDependencies() {
        // Mỗi test phải có dữ liệu độc lập; Spring giữ nguyên context và PostgreSQL
        // giữa các method nên không thể dựa vào thứ tự chạy hoặc ID của test trước.
        // loan_disbursement_sagas không có FK nên CASCADE không xóa; nhiều test đi tới EFFECTIVE (PAdES) cùng dùng
        // application_id 1 sau RESTART IDENTITY, saga sót lại sẽ làm DisbursementRequested của test sau bị bỏ qua.
        jdbcTemplate.execute("""
                TRUNCATE TABLE loan_disbursement_sagas, loan_processed_events,
                    loan_repayment_event_quarantine, loan_collection_actions, loan_collection_cases,
                    loan_reschedule_requests, loan_servicing_projections, finora_loans,
                    loan_outbox_events, loan_contract_signatures, loan_contract_documents,
                    loan_contract_status_histories, loan_contracts,
                    credit_scoring_retry_requests, credit_scoring_assessments,
                    borrower_eligibility_checks, borrower_credit_profiles,
                    loan_application_status_histories, schedule_calculation_snapshots,
                    loan_applications, fineract_commands, fineract_product_mappings, loan_products
                RESTART IDENTITY CASCADE
                """);
        fineractIds.set(1000);
        org.springframework.security.oauth2.jwt.Jwt mockJwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject("BORROWER-001")
                .claim("user_id", "BORROWER-001")
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(3600))
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        mockJwt,
                        java.util.List.of(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BORROWER"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")
                        ),
                        "BORROWER-001"
                )
        );
        when(productGateway.findProductByExternalId(anyString(), anyString())).thenReturn(Optional.empty());
        when(productGateway.createProduct(any(), anyString()))
                .thenAnswer(invocation -> new FineractProductCreationResult(fineractIds.incrementAndGet(), "{}"));
        when(scheduleGateway.calculateSchedule(any()))
                .thenAnswer(invocation -> schedule(invocation.getArgument(0)));
        when(borrowerProfileProvider.getBorrowerProfile(anyString(), any()))
                .thenReturn(new BorrowerProfileResult(
                        "BORROWER-001", null, 30, BorrowerKycStatus.VERIFIED,
                        "MOCK-KYC-BORROWER-001", "MOCK-V1", IncomeVerificationStatus.NOT_VERIFIED,
                        BorrowerProfileSource.MOCK_USER_PROFILE, java.time.Instant.parse("2026-08-03T00:00:00Z")));
        when(aiCreditScoringGateway.score(any(), anyString()))
                .thenReturn(new AiCreditScoreResponse(
                        new BigDecimal("0.31000000"), 72, new BigDecimal("70.2000"), "B",
                        AiRecommendation.PENDING_REVIEW,
                        objectMapper.createObjectNode().put("thong_diep", "Cần thẩm định"),
                        objectMapper.createObjectNode().put("gia_tri_co_so", 0),
                        objectMapper.createArrayNode(),
                        List.of(),
                        "17.0.0",
                        "CREDIT_POLICY_V1"));
    }

    @Test
    void migrationCreatesPostgreSqlSchemaOwnedByFlyway() {
        String databaseVersion = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        Long businessTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'
                """, Long.class);

        assertThat(databaseVersion).startsWith("17.");
        // V32 thêm loan_contract_signatures (chuỗi chữ ký PAdES).
        assertThat(businessTables).isEqualTo(25L);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void adminProductListSupportsFiltersAndUsesFixedQueryCount() throws Exception {
        JsonNode activeProduct = createActiveProduct();
        JsonNode draftProduct = createDraftProduct();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mockMvc.perform(get("/api/v1/admin/loan-products")
                            .queryParam("page", "0")
                            .queryParam("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.data.length()").value(2));
            assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(2L);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        mockMvc.perform(get("/api/v1/admin/loan-products")
                        .queryParam("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(draftProduct.path("id").asLong()))
                .andExpect(jsonPath("$.data[0].coreSyncStatus").value("NOT_SYNCED"));

        mockMvc.perform(get("/api/v1/admin/loan-products")
                        .queryParam("coreSyncStatus", "SYNCED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(activeProduct.path("id").asLong()))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    @Test
    void productMustSyncBeforeActivationAndApplicationStoresImmutableSnapshots() throws Exception {
        JsonNode product = createActiveProduct();
        String idempotencyKey = "submit-" + UUID.randomUUID();

        String body = mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "HOME_IMPROVEMENT", "Sửa mái nhà")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.productSnapshot.annualInterestRate").value(12.5))
                .andExpect(jsonPath("$.productSnapshot.fineractProductId").isNumber())
                .andExpect(jsonPath("$.financialInformation.annualIncomeSnapshot").value(240000000.0))
                .andExpect(jsonPath("$.financialInformation.dtiSnapshot").value(15.0))
                .andExpect(jsonPath("$.calculationSnapshot.totalRepayment").value(53000000.0))
                .andReturn().getResponse().getContentAsString();

        JsonNode application = objectMapper.readTree(body);
        String applicationNumber = application.path("applicationNumber").asText();

        // Gửi lại cùng key và cùng body phải trả đúng hồ sơ cũ.
        mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "HOME_IMPROVEMENT", "Sửa mái nhà")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationNumber").value(applicationNumber));

        mockMvc.perform(post("/api/v1/loan-applications/{number}/withdraw", applicationNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"Chưa có nhu cầu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        mockMvc.perform(get("/api/v1/loan-applications/{number}/history", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void reusingIdempotencyKeyForDifferentPayloadReturnsConflict() throws Exception {
        JsonNode product = createActiveProduct();
        String key = "submit-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "CAR", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void borrowerListUsesFixedQueryCountInsteadOfNPlusOne() throws Exception {
        JsonNode product = createActiveProduct();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/loan-applications")
                            .header("Idempotency-Key", "list-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                    .andExpect(status().isCreated());
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mockMvc.perform(get("/api/v1/loan-applications/me").queryParam("size", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3));
            assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(2L);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void submittedApplicationIsScoredAndAssessmentCanBeReviewed() throws Exception {
        JsonNode product = createActiveProduct();
        String body = mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", "score-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode submitted = objectMapper.readTree(body);

        // Test gọi cùng orchestrator mà worker dùng để không phụ thuộc thời gian scheduler.
        scoringOrchestrator.processApplication(submitted.path("id").asLong());

        mockMvc.perform(get("/api/v1/loan-applications/{number}", submitted.path("applicationNumber").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.latestCreditAssessmentId").isNumber());

        mockMvc.perform(get("/api/v1/admin/loan-applications/{number}/assessments",
                        submitted.path("applicationNumber").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].actualModelVersion").value("17.0.0"))
                .andExpect(jsonPath("$.data[0].creditGrade").value("B"));

        String storedResponse = jdbcTemplate.queryForObject(
                "SELECT response_snapshot_json::text FROM credit_scoring_assessments", String.class);
        assertThat(storedResponse).doesNotContain("suggested_rate").doesNotContain("suggestedRate");
    }

    @Test
    void approvalRequestsFundingAndOnlyFullyFundedEventCreatesContractIdempotently() throws Exception {
        JsonNode submitted = submitAndScoreApplication();
        String applicationNumber = submitted.path("applicationNumber").asText();

        JsonNode review = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/admin/loan-applications/{number}/review", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andReturn().getResponse().getContentAsString());
        long applicationVersion = review.path("version").asLong();
        long assessmentId = review.path("assessment").path("assessmentId").asLong();
        String approvalKey = "approve-" + UUID.randomUUID();
        String approvalJson = """
                {"applicationVersion":%d,"assessmentId":%d,
                 "decisionReasonCode":"POLICY_APPROVED",
                 "decisionReasonDetail":"Äiá»ƒm AI vÃ  kháº£ nÄƒng tráº£ ná»£ phÃ¹ há»£p"}
                """.formatted(applicationVersion, assessmentId);

        JsonNode approved = objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/admin/loan-applications/{number}/approve", applicationNumber)
                                .header("Idempotency-Key", approvalKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(approvalJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.termsConfirmationStatus").value("AUTO_AUTHORIZED"))
                .andExpect(jsonPath("$.contractNumber").doesNotExist())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/v1/admin/loan-applications")
                        .queryParam("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].applicationNumber").value(applicationNumber));
        mockMvc.perform(get("/api/v1/admin/loan-applications")
                        .queryParam("status", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Cùng Idempotency-Key không được phát thêm yêu cầu huy động vốn.
        mockMvc.perform(post("/api/v1/admin/loan-applications/{number}/approve", applicationNumber)
                        .header("Idempotency-Key", approvalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractNumber").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contracts", Long.class))
                .isZero();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM loan_outbox_events
                WHERE event_type = 'LoanFundingRequested' AND publishable = true
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT funding_status FROM loan_applications WHERE application_number = ?",
                String.class, applicationNumber)).isEqualTo("REQUESTED");
        mockMvc.perform(get("/api/v1/loan-applications/{number}", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funding.status").value("REQUESTED"))
                .andExpect(jsonPath("$.funding.investmentListingId").doesNotExist());

        List<FundingAllocationEventData> allocations = List.of(
                new FundingAllocationEventData(
                        101L, "INVESTOR-001", "50000000.00", "100.000000", "HOLD-INVESTOR-001"));
        String allocationHash = hashingService.sha256Text(hashingService.toJson(allocations));
        UUID fundedEventId = UUID.randomUUID();
        LoanFullyFundedEventData funded = new LoanFullyFundedEventData(
                submitted.path("id").asLong(), applicationNumber, 9001L, 1,
                "50000000.00", "VND", 1L, allocationHash, Instant.now(), allocations);
        fullyFundedHandler.handle(fundedEventId, Instant.now(), funded);
        fullyFundedHandler.handle(fundedEventId, Instant.now(), funded);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contracts", Long.class))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM loan_contracts", String.class))
                .isEqualTo("PENDING_LENDER_SIGNATURES");
        // Hợp đồng ghi đúng biểu phí trả trước in trong điều 4 để Payment tính phí theo hợp đồng (G-POLICY-W2).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT early_settlement_policy_version FROM loan_contracts", String.class))
                .isEqualTo("AGRIBANK_REFERENCE_FINORA_2026_V2");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM loan_outbox_events
                WHERE event_type = 'InvestorSignatureRequested' AND publishable = true
                """, Long.class)).isEqualTo(1L);

        mockMvc.perform(get("/api/v1/loan-applications/{number}", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.funding.status").value("FULLY_FUNDED"))
                .andExpect(jsonPath("$.funding.investmentListingId").value(9001));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT funding_status FROM loan_applications WHERE application_number = ?",
                String.class, applicationNumber)).isEqualTo("FULLY_FUNDED");

        org.springframework.security.oauth2.jwt.Jwt investorJwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("investor-token")
                        .header("alg", "RS256")
                        .subject("INVESTOR-001")
                        .claim("user_id", "INVESTOR-001")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        investorJwt,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_INVESTOR")),
                        "INVESTOR-001"));

        JsonNode investorContract = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/investor/loan-contracts/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partyStatus").value("PENDING_SIGNATURE"))
                .andExpect(jsonPath("$[0].availableSignatureProvider").value("MOCK"))
                .andReturn().getResponse().getContentAsString()).get(0);
        String contractNumber = investorContract.path("contractNumber").asText();
        String signJson = """
                {"version":%d,"documentHash":"%s","pdfDocumentHash":"%s",
                 "signatureMethod":"CLICK_WRAP_MVP"}
                """.formatted(
                investorContract.path("contractVersion").asLong(),
                investorContract.path("documentHash").asText(),
                investorContract.path("pdfDocumentHash").asText());

        // Token PIN gắn với đúng Idempotency-Key của lần ký (D38).
        String investorSignKey = "investor-sign-" + UUID.randomUUID();
        JsonNode signedInvestorContract = objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/investor/loan-contracts/{number}/sign", contractNumber)
                        .header(PinTokenService.HEADER_NAME,
                                pinTokenService.issue("INVESTOR-001", PinScope.SIGN_CONTRACT, investorSignKey).token())
                        .header("Idempotency-Key", investorSignKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNED"))
                .andExpect(jsonPath("$.contractStatus").value("PENDING_BORROWER_SIGNATURE"))
                .andReturn().getResponse().getContentAsString());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM loan_outbox_events
                WHERE event_type = 'BorrowerSignatureRequested' AND publishable = true
                """, Long.class)).isEqualTo(1L);
        // P1 (W3F): người vay nằm ngay trong event (ID logic, không phải PII) nên Notification không cần bảng tra.
        JsonNode borrowerTurn = objectMapper.readTree(jdbcTemplate.queryForObject("""
                SELECT payload_json::text FROM loan_outbox_events
                WHERE event_type = 'BorrowerSignatureRequested' AND event_version = 1
                """, String.class));
        assertThat(borrowerTurn.path("borrowerId").asText()).isEqualTo("BORROWER-001");
        assertThat(borrowerTurn.path("applicationNumber").asText()).isEqualTo(applicationNumber);

        org.springframework.security.oauth2.jwt.Jwt borrowerJwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("borrower-token")
                        .header("alg", "RS256")
                        .subject("BORROWER-001")
                        .claim("user_id", "BORROWER-001")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        borrowerJwt,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_BORROWER")),
                        "BORROWER-001"));

        JsonNode borrowerContract = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/loan-contracts/{number}", contractNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_BORROWER_SIGNATURE"))
                .andReturn().getResponse().getContentAsString());
        String borrowerSignJson = """
                {"version":%d,"documentHash":"%s","pdfDocumentHash":"%s",
                 "signatureMethod":"CLICK_WRAP_MVP"}
                """.formatted(
                borrowerContract.path("version").asLong(),
                borrowerContract.path("documentHash").asText(),
                borrowerContract.path("pdfDocument").path("contentHash").asText());
        String borrowerSignKey = "borrower-sign-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/loan-contracts/{number}/sign", contractNumber)
                        .header(PinTokenService.HEADER_NAME,
                                pinTokenService.issue("BORROWER-001", PinScope.SIGN_CONTRACT, borrowerSignKey).token())
                        .header("Idempotency-Key", borrowerSignKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(borrowerSignJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM loan_outbox_events
                WHERE event_type = 'DisbursementRequested'
                  AND event_version = 2
                  AND publishable = true
                  AND payload_json::text LIKE '%HOLD-INVESTOR-001%'
                """, Long.class)).isEqualTo(1L);
        // P2 (W3F): hợp đồng hiệu lực mang borrowerId; Blockchain neo hash cả data nên field là chuỗi.
        JsonNode activated = objectMapper.readTree(jdbcTemplate.queryForObject("""
                SELECT payload_json::text FROM loan_outbox_events
                WHERE event_type = 'LoanContractActivated' AND event_version = 1 AND publishable = true
                """, String.class));
        assertThat(activated.path("borrowerId").isTextual()).isTrue();
        assertThat(activated.path("borrowerId").asText()).isEqualTo("BORROWER-001");
        assertThat(activated.path("contractNumber").asText()).isEqualTo(contractNumber);

        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        investorJwt,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_INVESTOR")),
                        "INVESTOR-001"));
        JsonNode completedInvestorContract = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/investor/loan-contracts/{number}", contractNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("EFFECTIVE"))
                .andReturn().getResponse().getContentAsString());
        assertThat(completedInvestorContract.path("pdfDocumentHash").asText())
                .isNotEqualTo(signedInvestorContract.path("pdfDocumentHash").asText());
        // R5: sau khi đủ chữ ký, /document trả bản PAdES ký đủ (lender trước, borrower sau); biên nhận ở /receipt.
        assertThat(completedInvestorContract.path("signatureProfile").asText()).isEqualTo("PADES_B_B");
        assertThat(completedInvestorContract.path("pdfSignatureCount").asInt()).isEqualTo(2);
        byte[] signedContract = mockMvc.perform(
                        get("/api/v1/investor/loan-contracts/{number}/document", contractNumber))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith(MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(hashingService.sha256Bytes(signedContract))
                .isEqualTo(completedInvestorContract.path("pdfDocumentHash").asText())
                .isEqualTo(completedInvestorContract.path("signedPdfDocumentHash").asText());
        assertThat(padesVerifier.verify(signedContract))
                .extracting(signature -> signature.subjectCommonName())
                .containsExactly("FINORA DEMO - KHONG CO GIA TRI PHAP LY", "FINORA DEMO - KHONG CO GIA TRI PHAP LY");
        byte[] investorReceipt = mockMvc.perform(
                        get("/api/v1/investor/loan-contracts/{number}/receipt", contractNumber))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith(MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(hashingService.sha256Bytes(investorReceipt))
                .isEqualTo(completedInvestorContract.path("receiptDocumentHash").asText());
        try (org.apache.pdfbox.pdmodel.PDDocument pdf =
                     org.apache.pdfbox.pdmodel.PDDocument.load(investorReceipt)) {
            String receiptText = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(receiptText)
                    .contains("TRANG BIÊN NHẬN CHỮ KÝ SỐ")
                    .contains("ĐÃ KÝ")
                    .contains("INVESTOR-001")
                    .contains("BORROWER-001")
                    .contains("KHÔNG CÓ GIÁ TRỊ PHÁP LÝ")
                    .doesNotContain("CHƯA TÍCH HỢP");
        }
    }

    @Test
    void expiredFundedContractCancelsFundingAndPublishesCompensationOnce() throws Exception {
        JsonNode submitted = submitAndScoreApplication();
        String applicationNumber = submitted.path("applicationNumber").asText();
        JsonNode review = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/admin/loan-applications/{number}/review", applicationNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/v1/admin/loan-applications/{number}/approve", applicationNumber)
                        .header("Idempotency-Key", "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationVersion":%d,"assessmentId":%d,"decisionReasonCode":"POLICY_APPROVED"}
                                """.formatted(review.path("version").asLong(),
                                review.path("assessment").path("assessmentId").asLong())))
                .andExpect(status().isOk());
        List<FundingAllocationEventData> allocations = List.of(new FundingAllocationEventData(
                101L, "INVESTOR-001", "50000000.00", "100.000000", "HOLD-INVESTOR-001"));
        String allocationHash = hashingService.sha256Text(hashingService.toJson(allocations));
        fullyFundedHandler.handle(UUID.randomUUID(), Instant.now(), new LoanFullyFundedEventData(
                submitted.path("id").asLong(), applicationNumber, 9002L, 1,
                "50000000.00", "VND", 1L, allocationHash, Instant.now(), allocations));
        Long contractId = jdbcTemplate.queryForObject("SELECT id FROM loan_contracts", Long.class);

        // Không ai ký trong hạn: lùi created_at để vẫn thỏa ck_loan_contract_expiry (expires_at > created_at).
        jdbcTemplate.update("""
                UPDATE loan_contracts
                SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 minute'
                WHERE id = ?
                """, contractId);
        assertThat(contractStateService.expireOne(contractId)).isTrue();
        assertThat(contractStateService.expireOne(contractId)).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM loan_contracts WHERE id = ?", String.class, contractId)).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT funding_status, funding_cancel_reason FROM loan_applications WHERE application_number = ?",
                applicationNumber))
                .containsEntry("funding_status", "CANCELLED")
                .containsEntry("funding_cancel_reason", "CONTRACT_EXPIRED");
        // queryForObject chỉ nhận đúng một dòng: event bồi hoàn không được ghi lặp khi worker chạy lại.
        JsonNode cancelled = objectMapper.readTree(jdbcTemplate.queryForObject("""
                SELECT payload_json::text FROM loan_outbox_events
                WHERE event_type = 'LoanFundingCancelled' AND event_version = 1 AND publishable = true
                """, String.class));
        assertThat(cancelled.path("applicationNumber").asText()).isEqualTo(applicationNumber);
        assertThat(cancelled.path("listingId").asLong()).isEqualTo(9002L);
        assertThat(cancelled.path("allocationVersion").asLong()).isEqualTo(1L);
        assertThat(cancelled.path("allocationHash").asText()).isEqualTo(allocationHash);
        assertThat(cancelled.path("reasonCode").asText()).isEqualTo("CONTRACT_EXPIRED");
        // P2 (W3F): Notification báo người vay mà không cần bảng tra. ObjectMapper thật của Loan ghi java.time dạng chuỗi
        // ISO (điều Blockchain hash v2 và record LocalDate/Instant của consumer cần).
        assertThat(cancelled.path("borrowerId").asText()).isEqualTo("BORROWER-001");
        assertThat(cancelled.path("cancelledAt").isTextual()).isTrue();
        // Consumer Loan đọc bằng chính ObjectMapper này: field producer bổ sung (P4 borrowerId, P6 investorIds) bị bỏ qua.
        assertThat(objectMapper.isEnabled(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        mockMvc.perform(get("/api/v1/loan-applications/{number}", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funding.status").value("CANCELLED"))
                .andExpect(jsonPath("$.funding.cancelReason").value("CONTRACT_EXPIRED"));
    }

    /**
     * R5 (D13, Q9) với provider MOCK như E2E: lender ký trước, borrower ký cuối, mỗi chữ ký là một incremental update,
     * retry không tạo chữ ký thứ hai và mọi chữ ký trước vẫn hợp lệ trong tệp cuối.
     */
    @Test
    void padesSignaturesAreSequentialIdempotentAndEveryEarlierSignatureStillVerifies() throws Exception {
        String contractNumber = fundedContract(9101L, List.of(
                new FundingAllocationEventData(101L, "INVESTOR-001", "20000000.00", "40.000000", "HOLD-PADES-101"),
                new FundingAllocationEventData(102L, "INVESTOR-002", "20000000.00", "40.000000", "HOLD-PADES-102"),
                new FundingAllocationEventData(103L, "INVESTOR-001", "10000000.00", "20.000000", "HOLD-PADES-103")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT signature_profile FROM loan_contracts WHERE contract_number = ?",
                String.class, contractNumber)).isEqualTo("PADES_B_B");
        String signableHash = signableHash(contractNumber);

        // Người vay ký sau cùng: chưa đủ chữ ký bên cho vay thì bị chặn, chưa có bản ký số nào.
        actAs("BORROWER-001", "ROLE_BORROWER");
        JsonNode early = borrowerContract(contractNumber);
        assertThat(early.path("status").asText()).isEqualTo("PENDING_LENDER_SIGNATURES");
        signAsBorrower(contractNumber, "pades-borrower-early-" + UUID.randomUUID(), early, signableHash,
                "CLICK_WRAP_MVP")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONTRACT_TRANSITION"));
        mockMvc.perform(get("/api/v1/loan-contracts/{number}/signed-document", contractNumber))
                .andExpect(status().isNotFound());

        actAs("INVESTOR-001", "ROLE_INVESTOR");
        JsonNode lenderOne = investorContract(contractNumber);
        assertThat(lenderOne.path("pdfDocumentHash").asText()).isEqualTo(signableHash);
        String lenderOneKey = "pades-lender-1-" + UUID.randomUUID();
        signAsInvestor("INVESTOR-001", contractNumber, lenderOneKey, lenderOne, signableHash, "CLICK_WRAP_MVP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNED"))
                .andExpect(jsonPath("$.contractStatus").value("PENDING_LENDER_SIGNATURES"))
                .andExpect(jsonPath("$.pdfSignatureCount").value(1))
                .andExpect(jsonPath("$.pdfDocumentHash").value(signableHash));
        // Gửi lại cùng Idempotency-Key: trả kết quả cũ, không nhúng chữ ký thứ hai.
        signAsInvestor("INVESTOR-001", contractNumber, lenderOneKey, lenderOne, signableHash, "CLICK_WRAP_MVP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNED"))
                .andExpect(jsonPath("$.pdfSignatureCount").value(1));
        signAsInvestor("INVESTOR-001", contractNumber, "pades-lender-1-other-" + UUID.randomUUID(), lenderOne,
                signableHash, "CLICK_WRAP_MVP")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_PARTY_ALREADY_SIGNED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contract_signatures", Long.class))
                .isEqualTo(1L);

        actAs("INVESTOR-002", "ROLE_INVESTOR");
        JsonNode lenderTwo = investorContract(contractNumber);
        byte[] firstRevision = mockMvc.perform(
                        get("/api/v1/investor/loan-contracts/{number}/signed-document", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String firstRevisionHash = hashingService.sha256Bytes(firstRevision);
        assertThat(firstRevisionHash).isEqualTo(lenderTwo.path("signedPdfDocumentHash").asText());
        // D47: client có thể đồng ý trên đúng bản đang mang chữ ký của bên cho vay trước.
        signAsInvestor("INVESTOR-002", contractNumber, "pades-lender-2-" + UUID.randomUUID(), lenderTwo,
                firstRevisionHash, "CLICK_WRAP_MVP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNED"))
                .andExpect(jsonPath("$.contractStatus").value("PENDING_BORROWER_SIGNATURE"))
                .andExpect(jsonPath("$.pdfSignatureCount").value(2));

        actAs("BORROWER-001", "ROLE_BORROWER");
        JsonNode borrower = borrowerContract(contractNumber);
        // Trong lúc ký, /document vẫn là SIGNABLE để mọi bên đọc cùng một nội dung (tương thích E2E và mobile).
        assertThat(borrower.path("pdfDocument").path("contentHash").asText()).isEqualTo(signableHash);
        assertThat(borrower.path("signedPdfDocument").path("signatureCount").asInt()).isEqualTo(2);
        String borrowerKey = "pades-borrower-" + UUID.randomUUID();
        signAsBorrower(contractNumber, borrowerKey, borrower, signableHash, "CLICK_WRAP_MVP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));
        signAsBorrower(contractNumber, borrowerKey, borrower, signableHash, "CLICK_WRAP_MVP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        List<java.util.Map<String, Object>> chain = jdbcTemplate.queryForList("""
                SELECT s.signer_type, s.signer_id, s.status, s.base_content_hash, s.content_hash, s.content
                FROM loan_contract_signatures s JOIN loan_contracts c ON c.id = s.contract_id
                WHERE c.contract_number = ? ORDER BY s.sequence_number
                """, contractNumber);
        assertThat(chain).extracting(row -> row.get("status")).containsExactly("SIGNED", "SIGNED", "SIGNED");
        assertThat(chain).extracting(row -> row.get("signer_id"))
                .containsExactly("INVESTOR-001", "INVESTOR-002", "BORROWER-001");
        assertThat(chain).extracting(row -> row.get("signer_type")).containsExactly("LENDER", "LENDER", "BORROWER");
        assertThat(chain.get(0).get("base_content_hash")).isEqualTo(signableHash);
        assertThat(chain.get(1).get("base_content_hash")).isEqualTo(chain.get(0).get("content_hash"));
        assertThat(chain.get(2).get("base_content_hash")).isEqualTo(chain.get(1).get("content_hash"));

        JsonNode completed = borrowerContract(contractNumber);
        byte[] finalPdf = mockMvc.perform(get("/api/v1/loan-contracts/{number}/document", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(completed.path("pdfDocument").path("artifactType").asText()).isEqualTo("SIGNED_PDF");
        assertThat(hashingService.sha256Bytes(finalPdf))
                .isEqualTo(completed.path("pdfDocument").path("contentHash").asText())
                .isEqualTo(chain.get(2).get("content_hash"));
        // Bản xem trước để kiểm bằng trình đọc/validator ngoài (Acrobat, pyHanko, EU DSS); không commit.
        java.nio.file.Path preview = java.nio.file.Path.of("target", "test-output", "finora-pades-signed-preview.pdf");
        java.nio.file.Files.createDirectories(preview.getParent());
        java.nio.file.Files.write(preview, finalPdf);
        // Mỗi phiên bản trước là tiền tố nguyên vẹn của tệp cuối: chữ ký sau chỉ nối thêm bytes.
        for (java.util.Map<String, Object> row : chain) {
            byte[] revision = (byte[]) row.get("content");
            assertThat(java.util.Arrays.equals(finalPdf, 0, revision.length, revision, 0, revision.length)).isTrue();
        }
        var verified = padesVerifier.verify(finalPdf);
        assertThat(verified).extracting(signature -> signature.coveredLength()).containsExactly(
                ((byte[]) chain.get(0).get("content")).length, ((byte[]) chain.get(1).get("content")).length,
                finalPdf.length);
        assertThat(verified).allSatisfy(signature -> assertThat(signature.subjectCommonName())
                .isEqualTo("FINORA DEMO - KHONG CO GIA TRI PHAP LY"));
        byte[] signable = jdbcTemplate.queryForObject("""
                SELECT d.content FROM loan_contract_documents d JOIN loan_contracts c ON c.id = d.contract_id
                WHERE c.contract_number = ? AND d.artifact_type = 'SIGNABLE'
                """, byte[].class, contractNumber);
        try (org.apache.pdfbox.pdmodel.PDDocument original = org.apache.pdfbox.pdmodel.PDDocument.load(signable);
             org.apache.pdfbox.pdmodel.PDDocument signed = org.apache.pdfbox.pdmodel.PDDocument.load(finalPdf)) {
            assertThat(signed.getSignatureDictionaries()).hasSize(3)
                    .allSatisfy(dictionary -> assertThat(dictionary.getSubFilter()).isEqualTo("ETSI.CAdES.detached"));
            String signedText = new org.apache.pdfbox.text.PDFTextStripper().getText(signed);
            // Chữ ký ẩn: nội dung hiển thị của tệp đã ký trùng bản các bên đã đọc.
            assertThat(signedText).isEqualTo(new org.apache.pdfbox.text.PDFTextStripper().getText(original));
            assertThat(signedText).contains("PAdES-B-B").contains("_PADES");
        }
        byte[] receipt = mockMvc.perform(get("/api/v1/loan-contracts/{number}/receipt", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(completed.path("receiptDocument").path("contentHash").asText())
                .isEqualTo(hashingService.sha256Bytes(receipt));
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.pdmodel.PDDocument.load(receipt)) {
            assertThat(pdf.getSignatureDictionaries()).isEmpty();
            String receiptText = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            assertThat(receiptText)
                    .contains("TRANGBIÊNNHẬNCHỮKÝSỐ", "INVESTOR-001", "INVESTOR-002", "BORROWER-001")
                    .contains(hashingService.sha256Bytes(finalPdf));
        }
        // LoanContractActivated.v1 giữ nghĩa cũ của pdfReceiptHash: hash trang biên nhận.
        JsonNode activated = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload_json::text FROM loan_outbox_events WHERE event_type = 'LoanContractActivated'",
                String.class));
        assertThat(activated.path("pdfReceiptHash").asText()).isEqualTo(hashingService.sha256Bytes(receipt));
    }

    /** Hợp đồng lập trước V32 (DETACHED_EVIDENCE) giữ nguyên luồng click-wrap và receipt tách rời cũ. */
    @Test
    void contractCreatedBeforePadesKeepsDetachedClickWrapEvidence() throws Exception {
        String contractNumber = fundedContract(9201L, List.of(new FundingAllocationEventData(
                101L, "INVESTOR-001", "50000000.00", "100.000000", "HOLD-LEGACY-101")));
        jdbcTemplate.update("UPDATE loan_contracts SET signature_profile = 'DETACHED_EVIDENCE' WHERE contract_number = ?",
                contractNumber);
        String signableHash = signableHash(contractNumber);

        actAs("INVESTOR-001", "ROLE_INVESTOR");
        JsonNode lender = investorContract(contractNumber);
        assertThat(lender.path("signatureProfile").asText()).isEqualTo("DETACHED_EVIDENCE");
        signAsInvestor("INVESTOR-001", contractNumber, "legacy-lender-" + UUID.randomUUID(), lender, signableHash,
                "CLICK_WRAP_MVP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNED"))
                .andExpect(jsonPath("$.pdfSignatureCount").value(0));

        actAs("BORROWER-001", "ROLE_BORROWER");
        JsonNode borrower = borrowerContract(contractNumber);
        signAsBorrower(contractNumber, "legacy-borrower-" + UUID.randomUUID(), borrower, signableHash,
                "CLICK_WRAP_MVP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.signatureProvider").value("MOCK"))
                .andExpect(jsonPath("$.signatureMethod").value("CLICK_WRAP_MVP"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contract_signatures", Long.class))
                .isZero();
        JsonNode completed = borrowerContract(contractNumber);
        assertThat(completed.path("pdfDocument").path("artifactType").asText()).isEqualTo("SIGNED_RECEIPT");
        assertThat(completed.path("signedPdfDocument").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/loan-contracts/{number}/signed-document", contractNumber))
                .andExpect(status().isNotFound());
        byte[] receipt = mockMvc.perform(get("/api/v1/loan-contracts/{number}/document", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.pdmodel.PDDocument.load(receipt)) {
            assertThat(pdf.getSignatureDictionaries()).isEmpty();
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
                    .contains("ĐÃ KÝ")
                    .contains("INVESTOR-001");
        }
    }

    /**
     * Provider từ xa (giả lập SmartCA bằng spy, khóa test sinh trong test): một phiên chờ trên mỗi hợp đồng, worker thu
     * chữ ký khi người dùng không quay lại app, phiên bị từ chối/quá hạn/chữ ký sai được đóng để ký lại.
     */
    @Test
    void asyncProviderSignaturesAreSerializedPolledAndAbandonedSessionsReleaseTheTurn() throws Exception {
        TestSigner smartCa = testSigner("SMARTCA UAT TEST SIGNER");
        java.util.Map<String, String> statusByTransaction = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<String, byte[]> signatureByTransaction = new java.util.concurrent.ConcurrentHashMap<>();
        org.mockito.Mockito.doReturn(false).when(signatureProvider).completesSynchronously();
        org.mockito.Mockito.doReturn(com.finora.loan.domain.contract.SignatureProviderType.VNPT_SMART_CA)
                .when(signatureProvider).type();
        org.mockito.Mockito.doReturn(com.finora.loan.domain.contract.SignatureMethod.VNPT_SMART_CA)
                .when(signatureProvider).method();
        org.mockito.Mockito.doReturn(new com.finora.loan.integration.signature.SigningCertificate(
                        smartCa.certificate(), List.of()))
                .when(signatureProvider).signingCertificate(anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            com.finora.loan.integration.signature.DigestSignatureCommand command = invocation.getArgument(0);
            signatureByTransaction.put(command.providerTransactionId(),
                    smartCa.signDigest(java.util.HexFormat.of().parseHex(command.digestHex())));
            statusByTransaction.putIfAbsent(command.providerTransactionId(), "PENDING");
            return com.finora.loan.integration.signature.SignatureSubmission.pending(
                    command.providerTransactionId(), command.documentId(), "sig_wait_for_user_confirm");
        }).when(signatureProvider).submitDigest(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            String transactionId = invocation.getArgument(0);
            String documentId = invocation.getArgument(1);
            String mode = statusByTransaction.getOrDefault(transactionId, "NOT_FOUND");
            var completedStatus = com.finora.loan.integration.signature.SignatureSubmissionStatus.COMPLETED;
            return switch (mode) {
                case "COMPLETED" -> new com.finora.loan.integration.signature.SignatureSubmission(
                        completedStatus, transactionId, documentId,
                        java.util.Base64.getEncoder().encodeToString(signatureByTransaction.get(transactionId)),
                        null, "SUCCESS");
                case "INVALID" -> new com.finora.loan.integration.signature.SignatureSubmission(
                        completedStatus, transactionId, documentId,
                        java.util.Base64.getEncoder().encodeToString(new byte[256]), null, "SUCCESS");
                case "REJECTED" -> new com.finora.loan.integration.signature.SignatureSubmission(
                        com.finora.loan.integration.signature.SignatureSubmissionStatus.REJECTED,
                        transactionId, documentId, null, null, "sig_user_rejected");
                case "PENDING" -> com.finora.loan.integration.signature.SignatureSubmission.pending(
                        transactionId, documentId, "sig_wait_for_user_confirm");
                default -> new com.finora.loan.integration.signature.SignatureSubmission(
                        com.finora.loan.integration.signature.SignatureSubmissionStatus.NOT_FOUND,
                        transactionId, documentId, null, null, "NOT_FOUND");
            };
        }).when(signatureProvider).status(anyString(), anyString());

        String contractNumber = fundedContract(9301L, List.of(
                new FundingAllocationEventData(101L, "INVESTOR-001", "25000000.00", "50.000000", "HOLD-ASYNC-101"),
                new FundingAllocationEventData(102L, "INVESTOR-002", "25000000.00", "50.000000", "HOLD-ASYNC-102")));
        String signableHash = signableHash(contractNumber);

        actAs("INVESTOR-001", "ROLE_INVESTOR");
        JsonNode lenderOne = investorContract(contractNumber);
        signAsInvestor("INVESTOR-001", contractNumber, "async-1a-" + UUID.randomUUID(), lenderOne, signableHash,
                "VNPT_SMART_CA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNING"));
        String firstTransaction = inFlightTransaction();

        // Một phiên chờ trên mỗi hợp đồng: bên khác nhận 409 và thử lại sau, không tạo nhánh PDF thứ hai.
        actAs("INVESTOR-002", "ROLE_INVESTOR");
        JsonNode lenderTwo = investorContract(contractNumber);
        signAsInvestor("INVESTOR-002", contractNumber, "async-2-blocked-" + UUID.randomUUID(), lenderTwo,
                signableHash, "VNPT_SMART_CA")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_SIGNATURE_IN_PROGRESS"));

        // Người dùng từ chối trên app SmartCA: phiên đóng, ký lại bằng lần ký mới có transaction mới.
        statusByTransaction.put(firstTransaction, "REJECTED");
        actAs("INVESTOR-001", "ROLE_INVESTOR");
        mockMvc.perform(post("/api/v1/investor/loan-contracts/{number}/signature/refresh", contractNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("PENDING_SIGNATURE"));
        signAsInvestor("INVESTOR-001", contractNumber, "async-1b-" + UUID.randomUUID(), lenderOne, signableHash,
                "VNPT_SMART_CA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNING"));
        String secondTransaction = inFlightTransaction();
        assertThat(secondTransaction).isNotEqualTo(firstTransaction);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_number FROM loan_contract_signatures WHERE status = 'PREPARED'", Integer.class))
                .isEqualTo(2);

        // Người dùng xác nhận trên app rồi không quay lại FINORA: worker phía server thu chữ ký về.
        statusByTransaction.put(secondTransaction, "COMPLETED");
        pdfSignatureWorker.reconcileInFlightSignatures();
        JsonNode lenderOneSigned = investorContract(contractNumber);
        assertThat(lenderOneSigned.path("partyStatus").asText()).isEqualTo("SIGNED");
        assertThat(lenderOneSigned.path("pdfSignatureCount").asInt()).isEqualTo(1);

        // Phiên bỏ dở quá hạn được giải phóng cho lượt ký sau.
        actAs("INVESTOR-002", "ROLE_INVESTOR");
        signAsInvestor("INVESTOR-002", contractNumber, "async-2a-" + UUID.randomUUID(), lenderTwo, signableHash,
                "VNPT_SMART_CA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNING"));
        jdbcTemplate.update(
                "UPDATE loan_contract_signatures SET prepared_at = prepared_at - interval '1 hour' WHERE status = 'PREPARED'");
        pdfSignatureWorker.reconcileInFlightSignatures();
        assertThat(investorContract(contractNumber).path("partyStatus").asText()).isEqualTo("PENDING_SIGNATURE");

        // Chữ ký không khớp chứng thư không bao giờ được nhúng; client nhận lỗi sau khi phiên đã đóng.
        signAsInvestor("INVESTOR-002", contractNumber, "async-2b-" + UUID.randomUUID(), lenderTwo, signableHash,
                "VNPT_SMART_CA")
                .andExpect(status().isOk());
        statusByTransaction.put(inFlightTransaction(), "INVALID");
        mockMvc.perform(post("/api/v1/investor/loan-contracts/{number}/signature/refresh", contractNumber))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SIGNATURE_PROVIDER_INVALID_SIGNATURE"));
        JsonNode afterInvalid = investorContract(contractNumber);
        assertThat(afterInvalid.path("partyStatus").asText()).isEqualTo("PENDING_SIGNATURE");
        assertThat(afterInvalid.path("pdfSignatureCount").asInt()).isEqualTo(1);

        signAsInvestor("INVESTOR-002", contractNumber, "async-2c-" + UUID.randomUUID(), lenderTwo, signableHash,
                "VNPT_SMART_CA")
                .andExpect(status().isOk());
        statusByTransaction.put(inFlightTransaction(), "COMPLETED");
        mockMvc.perform(post("/api/v1/investor/loan-contracts/{number}/signature/refresh", contractNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyStatus").value("SIGNED"))
                .andExpect(jsonPath("$.contractStatus").value("PENDING_BORROWER_SIGNATURE"));

        actAs("BORROWER-001", "ROLE_BORROWER");
        JsonNode borrower = borrowerContract(contractNumber);
        signAsBorrower(contractNumber, "async-borrower-" + UUID.randomUUID(), borrower, signableHash, "VNPT_SMART_CA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNING"));
        statusByTransaction.put(inFlightTransaction(), "COMPLETED");
        mockMvc.perform(post("/api/v1/loan-contracts/{number}/signature/refresh", contractNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT abandon_reason FROM loan_contract_signatures WHERE status = 'ABANDONED' ORDER BY id",
                String.class))
                .containsExactly("PROVIDER_REJECTED", "SIGNING_SESSION_EXPIRED", "PROVIDER_SIGNATURE_INVALID");
        byte[] finalPdf = mockMvc.perform(get("/api/v1/loan-contracts/{number}/document", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        var verified = padesVerifier.verify(finalPdf);
        assertThat(verified).hasSize(3);
        assertThat(verified).allSatisfy(signature -> assertThat(signature.subjectCommonName())
                .isEqualTo("SMARTCA UAT TEST SIGNER"));
        byte[] receipt = mockMvc.perform(get("/api/v1/loan-contracts/{number}/receipt", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.pdmodel.PDDocument.load(receipt)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
                    .contains("VNPT SMARTCA MÔI TRƯỜNG UAT");
        }
    }

    @Test
    void worseningTermsWaitForBorrowerAndAcceptanceRequestsFunding() throws Exception {
        configureWorseningAiTerms();
        JsonNode submitted = submitAndScoreApplication();
        String applicationNumber = submitted.path("applicationNumber").asText();

        JsonNode approved = approvePendingReview(applicationNumber);
        assertThat(approved.path("applicationStatus").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("termsConfirmationStatus").asText()).isEqualTo("PENDING");
        assertThat(approved.path("contractNumber").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contracts", Long.class))
                .isZero();

        JsonNode application = borrowerApplication(applicationNumber);
        JsonNode terms = application.path("termsConfirmation");
        String acceptJson = """
                {"applicationVersion":%d,"termsVersion":"%s","termsHash":"%s"}
                """.formatted(
                application.path("version").asLong(),
                terms.path("termsVersion").asText(),
                terms.path("termsHash").asText());

        mockMvc.perform(post("/api/v1/loan-applications/{number}/terms/accept", applicationNumber)
                        .header("Idempotency-Key", "terms-accept-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsConfirmation.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.funding.status").value("REQUESTED"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contracts", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT funding_status FROM loan_applications WHERE application_number = ?",
                String.class, applicationNumber)).isEqualTo("REQUESTED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM loan_outbox_events
                WHERE event_type = 'LoanFundingRequested' AND publishable = true
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void worseningTermsCanBeDeclinedWithoutCreatingContract() throws Exception {
        configureWorseningAiTerms();
        JsonNode submitted = submitAndScoreApplication();
        String applicationNumber = submitted.path("applicationNumber").asText();
        JsonNode approved = approvePendingReview(applicationNumber);
        assertThat(approved.path("termsConfirmationStatus").asText()).isEqualTo("PENDING");

        JsonNode application = borrowerApplication(applicationNumber);
        JsonNode terms = application.path("termsConfirmation");
        String declineJson = """
                {"applicationVersion":%d,"termsVersion":"%s","termsHash":"%s",
                 "reasonCode":"TERMS_NOT_ACCEPTED","reasonDetail":"Repayment terms are not suitable"}
                """.formatted(
                application.path("version").asLong(),
                terms.path("termsVersion").asText(),
                terms.path("termsHash").asText());

        mockMvc.perform(post("/api/v1/loan-applications/{number}/terms/decline", applicationNumber)
                        .header("Idempotency-Key", "terms-decline-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declineJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsConfirmation.status").value("DECLINED"))
                .andExpect(jsonPath("$.termsConfirmation.declineReasonCode").value("TERMS_NOT_ACCEPTED"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contracts", Long.class))
                .isZero();
    }

    @Test
    void unansweredWorseningTermsExpireWithoutCreatingContract() throws Exception {
        configureWorseningAiTerms();
        JsonNode submitted = submitAndScoreApplication();
        String applicationNumber = submitted.path("applicationNumber").asText();
        JsonNode approved = approvePendingReview(applicationNumber);
        assertThat(approved.path("termsConfirmationStatus").asText()).isEqualTo("PENDING");

        Long applicationId = jdbcTemplate.queryForObject(
                "SELECT id FROM loan_applications WHERE application_number = ?",
                Long.class,
                applicationNumber);
        jdbcTemplate.update(
                "UPDATE loan_applications SET terms_expires_at = now() - interval '1 minute' WHERE id = ?",
                applicationId);

        assertThat(termsExpiryStateService.expireOne(applicationId)).isTrue();
        mockMvc.perform(get("/api/v1/loan-applications/{number}", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsConfirmation.status").value("EXPIRED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contracts", Long.class))
                .isZero();
    }

    @Test
    void adminApplicationListSupportsAllAndUsesBatchAssessmentQueryInsteadOfNPlusOne() throws Exception {
        JsonNode product = createActiveProduct();
        for (int i = 0; i < 3; i++) {
            JsonNode submitted = submitApplication(product, "review-" + UUID.randomUUID());
            scoringOrchestrator.processApplication(submitted.path("id").asLong());
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mockMvc.perform(get("/api/v1/admin/loan-applications")
                            .queryParam("size", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.data.length()").value(3));
            // Page content + count + batch assessment + một query fixed của projection liên quan.
            // Giới hạn vẫn cố định theo page, không tăng theo số hồ sơ.
            assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(4L);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        mockMvc.perform(get("/api/v1/admin/loan-applications")
                        .queryParam("status", "PENDING_REVIEW")
                        .queryParam("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    /**
     * Chạy câu SQL thống kê thật trên PostgreSQL 17: cắt cột theo giờ Việt Nam, nhóm nợ từ DPD,
     * phễu cohort và phân bố điểm. Mốc thời gian được đặt sát nửa đêm để sai múi giờ là lệch cột ngay.
     */
    @Test
    void adminLoanStatisticsAggregateRealSchemaInVietnamTimeBuckets() throws Exception {
        JsonNode approvedApplication = submitAndScoreApplication();
        approvePendingReview(approvedApplication.path("applicationNumber").asText());
        JsonNode pendingApplication = submitAndScoreApplication();
        long approvedId = approvedApplication.path("id").asLong();
        long pendingId = pendingApplication.path("id").asLong();

        // 23:30 UTC 1/10 = 06:30 ngày 2/10 giờ Việt Nam; 16:30 UTC 1/10 = 23:30 ngày 1/10 giờ Việt Nam.
        jdbcTemplate.update("""
                UPDATE loan_applications
                SET submitted_at = TIMESTAMPTZ '2026-10-01T23:30:00Z',
                    admin_decided_at = TIMESTAMPTZ '2026-10-03T00:00:00Z'
                WHERE id = ?
                """, approvedId);
        // Đổi số tiền hồ sơ chờ duyệt để hai cột có giá trị nộp khác nhau, cộng nhầm cột là thấy ngay.
        jdbcTemplate.update("""
                UPDATE loan_applications
                SET submitted_at = TIMESTAMPTZ '2026-10-01T16:30:00Z', requested_amount = 30000000.00
                WHERE id = ?
                """, pendingId);
        Long loanId = jdbcTemplate.queryForObject("""
                INSERT INTO finora_loans (loan_number, loan_application_id, application_number, contract_number,
                    borrower_id, fineract_loan_id, principal_amount, currency, status, disbursed_at,
                    created_at, updated_at)
                VALUES ('LN-STATS-001', ?, ?, 'CT-STATS-001', 'BORROWER-001', 777001, 50000000.00, 'VND',
                    'ACTIVE', TIMESTAMPTZ '2026-10-03T18:00:00Z', now(), now())
                RETURNING id
                """, Long.class, approvedId, approvedApplication.path("applicationNumber").asText());
        jdbcTemplate.update("""
                INSERT INTO loan_servicing_projections (finora_loan_id, fineract_status_code, principal_disbursed,
                    principal_paid, principal_outstanding, interest_charged, interest_paid, interest_outstanding,
                    fee_outstanding, penalty_outstanding, total_outstanding, overdue_amount, days_past_due,
                    source, data_as_of, last_synced_at, stale, created_at, updated_at)
                VALUES (?, 'loanStatusType.active', 50000000.00, 10000000.00, 40000000.00, 2000000.00, 0, 2000000.00,
                    0, 0, 42000000.00, 5000000.00, 120, 'FINERACT_SERVICING_SYNC', now(), now(), true, now(), now())
                """, loanId);

        mockMvc.perform(get("/api/v1/admin/loan-statistics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications.total").value(2))
                .andExpect(jsonPath("$.applications.byStatus.APPROVED").value(1))
                .andExpect(jsonPath("$.applications.byStatus.PENDING_REVIEW").value(1))
                .andExpect(jsonPath("$.applications.byFundingStatus.REQUESTED").value(1))
                .andExpect(jsonPath("$.portfolio.loansByStatus.ACTIVE").value(1))
                .andExpect(jsonPath("$.portfolio.outstandingLoans").value(1))
                .andExpect(jsonPath("$.portfolio.principalOutstanding").value(40000000.00))
                .andExpect(jsonPath("$.portfolio.totalOutstanding").value(42000000.00))
                .andExpect(jsonPath("$.portfolio.overdueAmount").value(5000000.00))
                // DPD 120 thuộc nhóm 3 nên toàn bộ dư nợ gốc là nợ xấu.
                .andExpect(jsonPath("$.portfolio.nplPrincipalOutstanding").value(40000000.00))
                .andExpect(jsonPath("$.portfolio.nplRatioPercent").value(100.00))
                .andExpect(jsonPath("$.portfolio.staleProjections").value(1))
                .andExpect(jsonPath("$.portfolio.byDebtGroup[2].loans").value(1))
                .andExpect(jsonPath("$.portfolio.byCreditGrade[0].grade").value("B"))
                .andExpect(jsonPath("$.portfolio.byCreditGrade[0].loans").value(1))
                .andExpect(jsonPath("$.portfolio.byProduct.length()").value(2))
                .andExpect(jsonPath("$.creditScores.assessed").value(2))
                .andExpect(jsonPath("$.creditScores.byGrade.B").value(2))
                // Điểm 70.2 rơi vào cột [70, 80).
                .andExpect(jsonPath("$.creditScores.histogram[7].count").value(2));

        mockMvc.perform(get("/api/v1/admin/loan-statistics/series")
                        .queryParam("from", "2026-10-01")
                        .queryParam("to", "2026-10-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(4))
                .andExpect(jsonPath("$.points[0].bucketStart").value("2026-10-01"))
                .andExpect(jsonPath("$.points[0].applicationsSubmitted").value(1))
                .andExpect(jsonPath("$.points[0].applicationsSubmittedAmount").value(30000000.00))
                .andExpect(jsonPath("$.points[1].applicationsSubmitted").value(1))
                .andExpect(jsonPath("$.points[1].applicationsSubmittedAmount").value(50000000.00))
                .andExpect(jsonPath("$.points[2].applicationsSubmittedAmount").value(0.0))
                .andExpect(jsonPath("$.points[2].applicationsApproved").value(1))
                .andExpect(jsonPath("$.points[3].loansDisbursed").value(1))
                .andExpect(jsonPath("$.points[3].disbursedAmount").value(50000000.00))
                .andExpect(jsonPath("$.productPoints.length()").value(3))
                .andExpect(jsonPath("$.productPoints[0].bucketStart").value("2026-10-01"))
                .andExpect(jsonPath("$.productPoints[0].applicationsSubmittedAmount").value(30000000.00))
                .andExpect(jsonPath("$.productPoints[1].bucketStart").value("2026-10-02"))
                .andExpect(jsonPath("$.productPoints[1].applicationsSubmittedAmount").value(50000000.00))
                .andExpect(jsonPath("$.productPoints[2].applicationsSubmittedAmount").value(0.0))
                .andExpect(jsonPath("$.funnel.submitted").value(2))
                .andExpect(jsonPath("$.funnel.scored").value(2))
                .andExpect(jsonPath("$.funnel.approved").value(1))
                .andExpect(jsonPath("$.funnel.termsAccepted").value(1))
                .andExpect(jsonPath("$.funnel.fundingRequested").value(1))
                .andExpect(jsonPath("$.funnel.fullyFunded").value(0))
                .andExpect(jsonPath("$.funnel.disbursed").value(1));

        mockMvc.perform(get("/api/v1/admin/loan-statistics/series")
                        .queryParam("from", "2026-10-01")
                        .queryParam("to", "2026-10-04")
                        .queryParam("bucket", "WEEK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-28"))
                .andExpect(jsonPath("$.points.length()").value(1))
                .andExpect(jsonPath("$.points[0].applicationsSubmitted").value(2))
                .andExpect(jsonPath("$.points[0].applicationsSubmittedAmount").value(80000000.00));
    }

    @Test
    @EnabledIfSystemProperty(named = "finora.live.ai", matches = "true")
    void dockerAiV17ScoresThroughLoanAndPersistsAssessment() throws Exception {
        // Dùng chính HTTP client production để ca live kiểm tra luôn timeout, contract và model version.
        AiCreditScoringGateway liveAi = new AiCreditScoringHttpClient(
                aiCreditRestClient, aiCreditProperties, circuitBreakerFactory);
        when(aiCreditScoringGateway.score(any(), anyString())).thenAnswer(invocation -> {
            AiCreditScoreRequest request = invocation.getArgument(0, AiCreditScoreRequest.class);
            return liveAi.score(request, invocation.getArgument(1, String.class));
        });

        JsonNode product = createActiveProduct();
        String body = mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", "live-ai-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode submitted = objectMapper.readTree(body);

        scoringOrchestrator.processApplication(submitted.path("id").asLong());

        String applicationBody = mockMvc.perform(
                        get("/api/v1/loan-applications/{number}", submitted.path("applicationNumber").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestCreditAssessmentId").isNumber())
                .andReturn().getResponse().getContentAsString();

        String assessmentsBody = mockMvc.perform(
                        get("/api/v1/admin/loan-applications/{number}/assessments",
                                submitted.path("applicationNumber").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].actualModelVersion").value("17.0.0"))
                .andExpect(jsonPath("$.data[0].aiRecommendation").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode application = objectMapper.readTree(applicationBody);
        JsonNode assessment = objectMapper.readTree(assessmentsBody).path("data").path(0);
        assertThat(application.path("status").asText())
                .isEqualTo(assessment.path("aiRecommendation").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_scoring_assessments WHERE status = 'SUCCEEDED'", Long.class))
                .isEqualTo(1L);
    }

    private JsonNode createActiveProduct() throws Exception {
        JsonNode created = createDraftProduct();

        String syncBody = mockMvc.perform(post("/api/v1/admin/loan-products/{id}/core-sync", created.path("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandStatus").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        long syncedVersion = objectMapper.readTree(syncBody).path("product").path("version").asLong();

        String activeBody = mockMvc.perform(post("/api/v1/admin/loan-products/{id}/activate", created.path("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + syncedVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(activeBody);
    }

    private JsonNode createDraftProduct() throws Exception {
        String code = "PERSONAL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String createdBody = mockMvc.perform(post("/api/v1/admin/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Sản phẩm tiêu chuẩn","description":"Lãi suất theo rủi ro",
                                 "minAmount":10000000,"maxAmount":100000000,"minTermMonths":6,"maxTermMonths":24,
                                 "minAnnualInterestRate":10.0,"annualInterestRate":12.5,
                                 "maxAnnualInterestRate":15.0,"repaymentMethod":"ANNUITY"}
                                """.formatted(code)))
                  .andExpect(status().isCreated())
                  .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(createdBody);
    }

    @Test
    void borrowerExposureLimitCountsOpenApplicationsAndRejectsOverLimit() throws Exception {
        JsonNode product = createActiveProduct();
        // Ba hồ sơ 50 triệu đang xử lý: tổng 150 triệu, đúng hạn mức cấu hình của IT nên vẫn nhận.
        JsonNode first = submitApplication(product, "exposure-1-" + UUID.randomUUID());
        submitApplication(product, "exposure-2-" + UUID.randomUUID());
        submitApplication(product, "exposure-3-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", "exposure-4-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BORROWER_EXPOSURE_LIMIT_EXCEEDED"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from loan_applications where borrower_id = 'BORROWER-001'", Integer.class))
                .isEqualTo(3);

        // Hồ sơ đã rút không còn tính vào dư nợ: nộp lại được.
        mockMvc.perform(post("/api/v1/loan-applications/{number}/withdraw", first.path("applicationNumber").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + first.path("version").asLong() + ",\"reason\":\"Đổi kế hoạch\"}"))
                .andExpect(status().isOk());
        submitApplication(product, "exposure-5-" + UUID.randomUUID());
    }

    @Test
    void onlyVerifiedBorrowerCanSubmitAndKycSnapshotIsStoredWithApplication() throws Exception {
        JsonNode product = createActiveProduct();
        JsonNode submitted = submitApplication(product, "kyc-ok-" + UUID.randomUUID());
        // Hồ sơ hợp lệ giữ snapshot eKYC lúc nộp; worker chấm điểm dùng lại bản này (W2-KYC).
        var snapshot = jdbcTemplate.queryForMap(
                "select kyc_snapshot_status, kyc_snapshot_age, kyc_snapshot_source, kyc_snapshot_captured_at"
                        + " from loan_applications where id = ?", submitted.path("id").asLong());
        assertThat(snapshot.get("kyc_snapshot_status")).isEqualTo("VERIFIED");
        assertThat(((Number) snapshot.get("kyc_snapshot_age")).intValue()).isEqualTo(30);
        assertThat(snapshot.get("kyc_snapshot_source")).isEqualTo("MOCK_USER_PROFILE");
        assertThat(snapshot.get("kyc_snapshot_captured_at")).isNotNull();

        when(borrowerProfileProvider.getBorrowerProfile(anyString(), any())).thenReturn(new BorrowerProfileResult(
                "BORROWER-001", null, 30, BorrowerKycStatus.PENDING, null, null,
                IncomeVerificationStatus.NOT_VERIFIED, BorrowerProfileSource.USER_SERVICE, Instant.now()));
        org.mockito.Mockito.clearInvocations(scheduleGateway);
        mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", "kyc-pending-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BORROWER_KYC_REQUIRED"));
        // Chặn trước Fineract: hồ sơ không được nhận thì không tính lịch.
        org.mockito.Mockito.verify(scheduleGateway, org.mockito.Mockito.never()).calculateSchedule(any());

        when(borrowerProfileProvider.getBorrowerProfile(anyString(), any())).thenThrow(
                new BorrowerProfileUnavailableException("USER_UNREACHABLE", "finora-user không phản hồi", null));
        mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", "kyc-outage-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_UNAVAILABLE"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from loan_applications where borrower_id = 'BORROWER-001'", Integer.class))
                .isEqualTo(1);
    }

    private JsonNode submitAndScoreApplication() throws Exception {
        JsonNode submitted = submitApplication(createActiveProduct(), "approval-" + UUID.randomUUID());
        scoringOrchestrator.processApplication(submitted.path("id").asLong());
        return submitted;
    }

    private void configureWorseningAiTerms() {
        when(aiCreditScoringGateway.score(any(), anyString()))
                .thenReturn(new AiCreditScoreResponse(
                        new BigDecimal("0.42000000"), 68, new BigDecimal("66.0000"), "C",
                        AiRecommendation.PENDING_REVIEW,
                        objectMapper.createObjectNode().put("thong_diep", "Can tham dinh"),
                        objectMapper.createObjectNode().put("gia_tri_co_so", 0),
                        objectMapper.createArrayNode(),
                        List.of(),
                        "17.0.0",
                        "CREDIT_POLICY_V1"));
    }

    private JsonNode approvePendingReview(String applicationNumber) throws Exception {
        JsonNode review = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/admin/loan-applications/{number}/review", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andReturn().getResponse().getContentAsString());
        String approvalJson = """
                {"applicationVersion":%d,"assessmentId":%d,
                 "decisionReasonCode":"POLICY_APPROVED",
                 "decisionReasonDetail":"Reviewed AI evidence and repayment capacity"}
                """.formatted(
                review.path("version").asLong(),
                review.path("assessment").path("assessmentId").asLong());
        String response = mockMvc.perform(
                        post("/api/v1/admin/loan-applications/{number}/approve", applicationNumber)
                                .header("Idempotency-Key", "approve-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(approvalJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode borrowerApplication(String applicationNumber) throws Exception {
        String response = mockMvc.perform(get("/api/v1/loan-applications/{number}", applicationNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode submitApplication(JsonNode product, String idempotencyKey) throws Exception {
        String body = mockMvc.perform(post("/api/v1/loan-applications")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(product.path("id").asLong(), "EDUCATION", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String applicationJson(long productId, String purpose, String detail) {
        String purposeDetail = detail == null ? "null" : "\"" + detail + "\"";
        return """
                {"loanProductId":%d,"requestedAmount":50000000,"requestedTermMonths":12,
                 "purposeCode":"%s","purposeDetail":%s,"declaredMonthlyIncome":20000000,
                 "employmentLengthMonths":60,"educationLevel":"UNIVERSITY","homeOwnership":"RENT",
                 "monthlyDebtObligations":3000000,"expectedDisbursementDate":"%s",
                 "pricingDisclosureVersion":"RATE_DISCLOSURE_V2","pricingDisclosureAccepted":true}
                """.formatted(productId, purpose, purposeDetail, EXPECTED_DISBURSEMENT_DATE);
    }

    private ScheduleCalculationResult schedule(ScheduleCalculationRequest request) {
        SchedulePeriod period = new SchedulePeriod(
                1, request.expectedDisbursementDate(), request.expectedDisbursementDate().plusMonths(1),
                30, request.amount(), new BigDecimal("3000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("53000000"), BigDecimal.ZERO);
        return new ScheduleCalculationResult(
                request.amount(), request.termMonths(), request.annualInterestRate(), request.repaymentMethod(),
                request.expectedDisbursementDate(), new BigDecimal("53000000"), new BigDecimal("53000000"),
                request.amount(), new BigDecimal("3000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("53000000"), List.of(period), "{}", "[]",
                "FINERACT_1_15_SCHEDULE_V1", "0".repeat(64));
    }

    /** Đi hết nộp hồ sơ → chấm điểm → admin duyệt → LoanFullyFunded với allocation cho trước; trả số hợp đồng. */
    private String fundedContract(long listingId, List<FundingAllocationEventData> allocations) throws Exception {
        actAs("BORROWER-001", "ROLE_BORROWER", "ROLE_ADMIN");
        JsonNode submitted = submitAndScoreApplication();
        String applicationNumber = submitted.path("applicationNumber").asText();
        approvePendingReview(applicationNumber);
        String allocationHash = hashingService.sha256Text(hashingService.toJson(allocations));
        fullyFundedHandler.handle(UUID.randomUUID(), Instant.now(), new LoanFullyFundedEventData(
                submitted.path("id").asLong(), applicationNumber, listingId, 1,
                "50000000.00", "VND", 1L, allocationHash, Instant.now(), allocations));
        return jdbcTemplate.queryForObject(
                "SELECT contract_number FROM loan_contracts WHERE application_id = ?",
                String.class, submitted.path("id").asLong());
    }

    private String signableHash(String contractNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT d.content_hash FROM loan_contract_documents d JOIN loan_contracts c ON c.id = d.contract_id
                WHERE c.contract_number = ? AND d.artifact_type = 'SIGNABLE'
                """, String.class, contractNumber);
    }

    private String inFlightTransaction() {
        return jdbcTemplate.queryForObject(
                "SELECT provider_transaction_id FROM loan_contract_signatures WHERE status = 'PREPARED'",
                String.class);
    }

    private void actAs(String userId, String... roles) {
        org.springframework.security.oauth2.jwt.Jwt jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue(userId + "-token")
                        .header("alg", "RS256")
                        .subject(userId)
                        .claim("user_id", userId)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        jwt,
                        java.util.Arrays.stream(roles)
                                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                                .toList(),
                        userId));
    }

    private JsonNode investorContract(String contractNumber) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/investor/loan-contracts/{number}", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode borrowerContract(String contractNumber) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/v1/loan-contracts/{number}", contractNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions signAsInvestor(
            String investorId,
            String contractNumber,
            String idempotencyKey,
            JsonNode view,
            String pdfDocumentHash,
            String signatureMethod
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/investor/loan-contracts/{number}/sign", contractNumber)
                .header(PinTokenService.HEADER_NAME,
                        pinTokenService.issue(investorId, PinScope.SIGN_CONTRACT, idempotencyKey).token())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"version":%d,"documentHash":"%s","pdfDocumentHash":"%s","signatureMethod":"%s"}
                        """.formatted(view.path("contractVersion").asLong(), view.path("documentHash").asText(),
                        pdfDocumentHash, signatureMethod)));
    }

    private org.springframework.test.web.servlet.ResultActions signAsBorrower(
            String contractNumber,
            String idempotencyKey,
            JsonNode view,
            String pdfDocumentHash,
            String signatureMethod
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/loan-contracts/{number}/sign", contractNumber)
                .header(PinTokenService.HEADER_NAME,
                        pinTokenService.issue("BORROWER-001", PinScope.SIGN_CONTRACT, idempotencyKey).token())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"version":%d,"documentHash":"%s","pdfDocumentHash":"%s","signatureMethod":"%s"}
                        """.formatted(view.path("version").asLong(), view.path("documentHash").asText(),
                        pdfDocumentHash, signatureMethod)));
    }

    /** Khóa RSA và chứng thư tự ký sinh trong test để giả lập thuê bao SmartCA; không có khóa nào được commit. */
    private record TestSigner(java.security.PrivateKey privateKey, java.security.cert.X509Certificate certificate) {

        /** Ký hash như dịch vụ "sign_type = hash": RSA PKCS#1 v1.5 trên DigestInfo(SHA-256, digest). */
        byte[] signDigest(byte[] digest) throws Exception {
            byte[] digestInfo = new org.bouncycastle.asn1.x509.DigestInfo(
                    new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                            org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256,
                            org.bouncycastle.asn1.DERNull.INSTANCE), digest).getEncoded("DER");
            java.security.Signature signature = java.security.Signature.getInstance("NONEwithRSA");
            signature.initSign(privateKey);
            signature.update(digestInfo);
            return signature.sign();
        }
    }

    private static TestSigner testSigner(String commonName) throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=" + commonName + ", O=FINORA Test, C=VN");
        Instant now = Instant.now();
        org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder =
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                        subject, java.math.BigInteger.valueOf(now.toEpochMilli()),
                        java.util.Date.from(now.minusSeconds(3600)), java.util.Date.from(now.plusSeconds(86400)),
                        subject, keyPair.getPublic());
        java.security.cert.X509Certificate certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(builder.build(new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(
                        "SHA256withRSA").build(keyPair.getPrivate())));
        return new TestSigner(keyPair.getPrivate(), certificate);
    }
}
