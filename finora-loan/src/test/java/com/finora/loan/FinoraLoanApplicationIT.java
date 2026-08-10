package com.finora.loan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.config.AiCreditProperties;
import com.finora.loan.config.MockCurrentUserProvider;
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
import com.finora.loan.service.scoring.CreditScoringOrchestrator;
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
        "finora.loan.contract.expiry-worker-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class FinoraLoanApplicationIT {

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
    @Autowired AiCreditProperties aiCreditProperties;
    @Autowired CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @Autowired @Qualifier("aiCreditRestClient") RestClient aiCreditRestClient;

    @MockBean MockCurrentUserProvider currentUser;
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
                TRUNCATE TABLE loan_contract_status_histories, loan_contracts,
                    credit_scoring_retry_requests, credit_scoring_assessments,
                    borrower_eligibility_checks, borrower_credit_profiles,
                    loan_application_status_histories, schedule_calculation_snapshots,
                    loan_applications, fineract_commands, fineract_product_mappings, loan_products
                RESTART IDENTITY CASCADE
                """);
        fineractIds.set(1000);
        when(currentUser.adminUserId()).thenReturn("ADMIN-001");
        when(currentUser.borrowerUserId()).thenReturn("BORROWER-001");
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
                        new BigDecimal("50000000.00"), new BigDecimal("0.15"),
                        AiRecommendation.PENDING_REVIEW, null, "10.0.0"));
    }

    @Test
    void migrationCreatesPostgreSqlSchemaOwnedByFlyway() {
        String databaseVersion = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        Long businessTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'
                """, Long.class);

        assertThat(databaseVersion).startsWith("17.");
        assertThat(businessTables).isEqualTo(12L);
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
                .andExpect(jsonPath("$.data[0].actualModelVersion").value("10.0.0"))
                .andExpect(jsonPath("$.data[0].creditGrade").value("B"));

        String storedResponse = jdbcTemplate.queryForObject(
                "SELECT response_snapshot_json::text FROM credit_scoring_assessments", String.class);
        assertThat(storedResponse).doesNotContain("suggested_rate").doesNotContain("suggestedRate");
    }

    @Test
    void adminApprovesAndBorrowerSignsExactGeneratedContractIdempotently() throws Exception {
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
                .andExpect(jsonPath("$.contractStatus").value("PENDING_SIGNATURE"))
                .andExpect(jsonPath("$.documentHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andReturn().getResponse().getContentAsString());
        String contractNumber = approved.path("contractNumber").asText();

        mockMvc.perform(get("/api/v1/admin/loan-applications")
                        .queryParam("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].applicationNumber").value(applicationNumber));
        mockMvc.perform(get("/api/v1/admin/loan-applications")
                        .queryParam("status", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // CÃ¹ng Idempotency-Key vÃ  cÃ¹ng payload khÃ´ng Ä‘Æ°á»£c táº¡o thÃªm há»£p Ä‘á»“ng.
        mockMvc.perform(post("/api/v1/admin/loan-applications/{number}/approve", applicationNumber)
                        .header("Idempotency-Key", approvalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractNumber").value(contractNumber));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loan_contracts", Long.class))
                .isEqualTo(1L);

        JsonNode contract = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/loan-contracts/{number}", contractNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_SIGNATURE"))
                .andExpect(jsonPath("$.documentContent").isNotEmpty())
                .andExpect(jsonPath("$.principalAmount").value(50000000.0))
                .andReturn().getResponse().getContentAsString());
        String signKey = "sign-" + UUID.randomUUID();
        String signJson = """
                {"version":%d,"documentHash":"%s","signatureMethod":"CLICK_WRAP_MVP"}
                """.formatted(contract.path("version").asLong(), contract.path("documentHash").asText());

        mockMvc.perform(post("/api/v1/loan-contracts/{number}/sign", contractNumber)
                        .header("Idempotency-Key", signKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
        mockMvc.perform(post("/api/v1/loan-contracts/{number}/sign", contractNumber)
                        .header("Idempotency-Key", signKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));

        mockMvc.perform(get("/api/v1/loan-contracts/{number}/history", contractNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/loan-applications/{number}", applicationNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
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
    void dockerAiV10ScoresThroughLoanAndPersistsAssessment() throws Exception {
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

        mockMvc.perform(get("/api/v1/loan-applications/{number}", submitted.path("applicationNumber").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.latestCreditAssessmentId").isNumber());

        mockMvc.perform(get("/api/v1/admin/loan-applications/{number}/assessments",
                        submitted.path("applicationNumber").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].actualModelVersion").value("10.0.0"));
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
                                {"code":"%s","name":"Sản phẩm tiêu chuẩn","description":"Lãi suất cố định",
                                 "minAmount":10000000,"maxAmount":100000000,"minTermMonths":6,"maxTermMonths":24,
                                 "annualInterestRate":12.5,"repaymentMethod":"ANNUITY"}
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
                 "monthlyDebtObligations":3000000,"expectedDisbursementDate":"2026-08-10",
                 "pricingDisclosureVersion":"RATE_DISCLOSURE_V1","pricingDisclosureAccepted":true}
                """.formatted(productId, purpose, purposeDetail);
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
