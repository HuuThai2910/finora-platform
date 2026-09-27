package com.finora.loan;

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
        "finora.signature.provider=mock"
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
    @Autowired HashingService hashingService;
    @Autowired AiCreditProperties aiCreditProperties;
    @Autowired CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @Autowired @Qualifier("aiCreditRestClient") RestClient aiCreditRestClient;

    @MockBean FineractLoanProductGateway productGateway;
    @MockBean FineractScheduleGateway scheduleGateway;
    @MockBean BorrowerProfileProvider borrowerProfileProvider;
    @MockBean AiCreditScoringGateway aiCreditScoringGateway;

    private final AtomicLong fineractIds = new AtomicLong(1000);

    @BeforeEach
    void configureDeterministicDependencies() {
        // Mỗi test phải có dữ liệu độc lập; Spring giữ nguyên context và PostgreSQL
        // giữa các method nên không thể dựa vào thứ tự chạy hoặc ID của test trước.
        jdbcTemplate.execute("""
                TRUNCATE TABLE loan_outbox_events, loan_contract_documents, loan_contract_status_histories, loan_contracts,
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
        when(productGateway.findProductByExternalId(anyString())).thenReturn(Optional.empty());
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
        assertThat(businessTables).isEqualTo(16L);
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
                new FundingAllocationEventData(101L, "INVESTOR-001", "50000000.00", "100.000000"));
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

        JsonNode signedInvestorContract = objectMapper.readTree(mockMvc.perform(
                        post("/api/v1/investor/loan-contracts/{number}/sign", contractNumber)
                        .header("Idempotency-Key", "investor-sign-" + UUID.randomUUID())
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
        mockMvc.perform(post("/api/v1/loan-contracts/{number}/sign", contractNumber)
                        .header("Idempotency-Key", "borrower-sign-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(borrowerSignJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

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
        byte[] investorReceipt = mockMvc.perform(
                        get("/api/v1/investor/loan-contracts/{number}/document", contractNumber))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith(MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse().getContentAsByteArray();
        try (org.apache.pdfbox.pdmodel.PDDocument pdf =
                     org.apache.pdfbox.pdmodel.PDDocument.load(investorReceipt)) {
            String receiptText = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(receiptText)
                    .contains("ĐÃ KÝ")
                    .contains("INVESTOR-001")
                    .doesNotContain("CHƯA TÍCH HỢP");
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
            assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(3L);
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
}
