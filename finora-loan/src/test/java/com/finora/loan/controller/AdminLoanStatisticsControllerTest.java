package com.finora.loan.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.finora.common.exception.GlobalExceptionHandler;
import com.finora.loan.repository.statistics.LoanStatisticsRepository;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.BucketProductCountRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.BucketProductDisbursementRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.FunnelRow;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.OutstandingByProductDpdRow;
import com.finora.loan.service.statistics.AdminLoanStatisticsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Kiểm tra hợp đồng HTTP mà web đang dựng song song: tên field, mã lỗi 400 và 403.
 * Dùng service thật trên repository giả để JSON đi qua đúng logic tổng hợp.
 */
@ExtendWith(MockitoExtension.class)
class AdminLoanStatisticsControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T01:00:00Z");

    @Mock
    private LoanStatisticsRepository repository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminLoanStatisticsService service =
                new AdminLoanStatisticsService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        // Giống cấu hình Jackson mặc định của Spring Boot: ngày giờ dạng ISO-8601, không phải mảng số.
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminLoanStatisticsController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .build();
        authenticateAs("ROLE_ADMIN");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void summaryExposesContractFieldNames() throws Exception {
        when(repository.countApplicationsByStatusAndFunding()).thenReturn(List.of());
        when(repository.countLoansByStatus()).thenReturn(List.of());
        when(repository.sumOutstandingByProductAndDpd()).thenReturn(List.of(new OutstandingByProductDpdRow(
                1L, 120, 1, new BigDecimal("100.00"), new BigDecimal("110.00"), new BigDecimal("30.00"), 0)));
        when(repository.sumOutstandingByCreditGrade()).thenReturn(List.of());
        when(repository.countApplicationsByProduct()).thenReturn(List.of(
                new LoanStatisticsRepository.ProductApplicationsRow(1L, "VAY_NHANH", "Vay nhanh", 2)));
        when(repository.countCollectionCasesByStageAndStatus()).thenReturn(List.of());
        when(repository.countReschedulesByStatus()).thenReturn(List.of());
        when(repository.countReconciliationIncidentsByStatus()).thenReturn(List.of());
        when(repository.countLatestSucceededScoresByGradeAndScore()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/loan-statistics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-10-08T01:00:00Z"))
                .andExpect(jsonPath("$.applications.total").value(0))
                .andExpect(jsonPath("$.applications.byStatus.PENDING_REVIEW").value(0))
                .andExpect(jsonPath("$.applications.byFundingStatus.FULLY_FUNDED").value(0))
                .andExpect(jsonPath("$.portfolio.loansByStatus.ACTIVE").value(0))
                .andExpect(jsonPath("$.portfolio.outstandingLoans").value(1))
                .andExpect(jsonPath("$.portfolio.principalOutstanding").value(100.00))
                .andExpect(jsonPath("$.portfolio.totalOutstanding").value(110.00))
                .andExpect(jsonPath("$.portfolio.overdueAmount").value(30.00))
                .andExpect(jsonPath("$.portfolio.nplPrincipalOutstanding").value(100.00))
                .andExpect(jsonPath("$.portfolio.nplRatioPercent").value(100.00))
                .andExpect(jsonPath("$.portfolio.staleProjections").value(0))
                .andExpect(jsonPath("$.portfolio.byDebtGroup.length()").value(5))
                .andExpect(jsonPath("$.portfolio.byDebtGroup[2].debtGroup").value(3))
                .andExpect(jsonPath("$.portfolio.byDebtGroup[2].loans").value(1))
                .andExpect(jsonPath("$.portfolio.byDebtGroup[2].principalOutstanding").value(100.00))
                .andExpect(jsonPath("$.portfolio.byDebtGroup[2].overdueAmount").value(30.00))
                .andExpect(jsonPath("$.portfolio.byCreditGrade").isArray())
                .andExpect(jsonPath("$.portfolio.byProduct[0].productId").value(1))
                .andExpect(jsonPath("$.portfolio.byProduct[0].productCode").value("VAY_NHANH"))
                .andExpect(jsonPath("$.portfolio.byProduct[0].productName").value("Vay nhanh"))
                .andExpect(jsonPath("$.portfolio.byProduct[0].applications").value(2))
                .andExpect(jsonPath("$.portfolio.byProduct[0].outstandingLoans").value(1))
                .andExpect(jsonPath("$.portfolio.byProduct[0].principalOutstanding").value(100.00))
                .andExpect(jsonPath("$.portfolio.byProduct[0].nplPrincipalOutstanding").value(100.00))
                .andExpect(jsonPath("$.portfolio.byProduct[0].nplRatioPercent").value(100.00))
                .andExpect(jsonPath("$.collections.openCases").value(0))
                .andExpect(jsonPath("$.collections.openByStage.EARLY_REMINDER").value(0))
                .andExpect(jsonPath("$.collections.byStatus.OPEN").value(0))
                .andExpect(jsonPath("$.reschedules.byStatus.PENDING_REVIEW").value(0))
                .andExpect(jsonPath("$.reconciliationIncidents.byStatus.OPEN").value(0))
                .andExpect(jsonPath("$.creditScores.assessed").value(0))
                .andExpect(jsonPath("$.creditScores.byGrade").isMap())
                .andExpect(jsonPath("$.creditScores.histogram.length()").value(10))
                .andExpect(jsonPath("$.creditScores.histogram[9].from").value(90))
                .andExpect(jsonPath("$.creditScores.histogram[9].to").value(100))
                .andExpect(jsonPath("$.creditScores.histogram[9].count").value(0));
    }

    @Test
    void seriesExposesContractFieldNamesWithIsoDates() throws Exception {
        when(repository.countSubmittedByBucketAndProduct(any())).thenReturn(List.of(new BucketProductCountRow(
                LocalDate.of(2026, 10, 4), 3L, 2, new BigDecimal("70000000.00"))));
        when(repository.countDecisionsByBucket(any())).thenReturn(List.of());
        when(repository.sumDisbursedByBucketAndProduct(any())).thenReturn(List.of(new BucketProductDisbursementRow(
                LocalDate.of(2026, 10, 3), 3L, 1, new BigDecimal("85000000.00"))));
        when(repository.countFunnel(any())).thenReturn(new FunnelRow(60, 58, 31, 25, 22, 15, 12));

        mockMvc.perform(get("/api/v1/admin/loan-statistics/series")
                        .queryParam("from", "2026-10-03")
                        .queryParam("to", "2026-10-04")
                        .queryParam("bucket", "DAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-03"))
                .andExpect(jsonPath("$.to").value("2026-10-04"))
                .andExpect(jsonPath("$.bucket").value("DAY"))
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].bucketStart").value("2026-10-03"))
                .andExpect(jsonPath("$.points[0].applicationsSubmitted").value(0))
                .andExpect(jsonPath("$.points[0].applicationsSubmittedAmount").value(0.0))
                .andExpect(jsonPath("$.points[1].applicationsSubmitted").value(2))
                .andExpect(jsonPath("$.points[1].applicationsSubmittedAmount").value(70000000.00))
                .andExpect(jsonPath("$.points[0].applicationsApproved").value(0))
                .andExpect(jsonPath("$.points[0].applicationsRejected").value(0))
                .andExpect(jsonPath("$.points[0].loansDisbursed").value(1))
                .andExpect(jsonPath("$.points[0].disbursedAmount").value(85000000.00))
                .andExpect(jsonPath("$.points[1].disbursedAmount").value(0.0))
                .andExpect(jsonPath("$.productPoints.length()").value(2))
                .andExpect(jsonPath("$.productPoints[0].bucketStart").value("2026-10-03"))
                .andExpect(jsonPath("$.productPoints[0].productId").value(3))
                .andExpect(jsonPath("$.productPoints[0].applicationsSubmitted").value(0))
                .andExpect(jsonPath("$.productPoints[0].applicationsSubmittedAmount").value(0.0))
                .andExpect(jsonPath("$.productPoints[0].disbursedAmount").value(85000000.00))
                .andExpect(jsonPath("$.productPoints[1].bucketStart").value("2026-10-04"))
                .andExpect(jsonPath("$.productPoints[1].applicationsSubmitted").value(2))
                .andExpect(jsonPath("$.productPoints[1].applicationsSubmittedAmount").value(70000000.00))
                .andExpect(jsonPath("$.productPoints[1].disbursedAmount").value(0.0))
                .andExpect(jsonPath("$.funnel.submitted").value(60))
                .andExpect(jsonPath("$.funnel.scored").value(58))
                .andExpect(jsonPath("$.funnel.approved").value(31))
                .andExpect(jsonPath("$.funnel.termsAccepted").value(25))
                .andExpect(jsonPath("$.funnel.fundingRequested").value(22))
                .andExpect(jsonPath("$.funnel.fullyFunded").value(15))
                .andExpect(jsonPath("$.funnel.disbursed").value(12));
    }

    @ParameterizedTest
    @CsvSource({
            "from=2026-10-08&to=2026-10-01&bucket=DAY",
            "from=2024-01-01&to=2026-10-01&bucket=DAY",
            "from=08/10/2026",
            "to=2026-13-01",
            "bucket=YEAR"})
    void invalidRangeOrParameterReturns400WithStableCode(String query) throws Exception {
        mockMvc.perform(get("/api/v1/admin/loan-statistics/series?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATISTICS_RANGE_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void lowercaseBucketIsAccepted() throws Exception {
        when(repository.countSubmittedByBucketAndProduct(any())).thenReturn(List.of());
        when(repository.countDecisionsByBucket(any())).thenReturn(List.of());
        when(repository.sumDisbursedByBucketAndProduct(any())).thenReturn(List.of());
        when(repository.countFunnel(any())).thenReturn(new FunnelRow(0, 0, 0, 0, 0, 0, 0));

        mockMvc.perform(get("/api/v1/admin/loan-statistics/series?from=2026-09-15&to=2026-10-08&bucket=month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("MONTH"))
                .andExpect(jsonPath("$.from").value("2026-09-01"))
                .andExpect(jsonPath("$.to").value("2026-10-31"))
                .andExpect(jsonPath("$.points.length()").value(2));
    }

    @Test
    void nonAdminGets403OnBothEndpoints() throws Exception {
        authenticateAs("ROLE_BORROWER");

        mockMvc.perform(get("/api/v1/admin/loan-statistics/summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        mockMvc.perform(get("/api/v1/admin/loan-statistics/series"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        verifyNoInteractions(repository);
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, role));
    }
}
