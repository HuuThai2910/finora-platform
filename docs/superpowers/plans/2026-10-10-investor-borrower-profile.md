# Hồ sơ người vay cho nhà đầu tư — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nhà đầu tư xem được hồ sơ người vay (ẩn danh, không SHAP) trên màn "Khoản vay #id" của app trước khi góp vốn.

**Architecture:** Loan (nguồn chuẩn của hồ sơ + kết quả chấm điểm) mở API chỉ đọc `GET /api/v1/investor/loan-applications/{applicationNumber}/borrower-profile`, dựng response theo allowlist từ các snapshot đã lưu. Investment trả thêm `applicationNumber` trong listing để app biết hồ sơ nào cần tải. Mobile thêm thẻ tóm tắt trên màn khoản vay và màn "Hồ sơ người vay".

**Tech Stack:** Spring Boot 3.2 / Java 21 / JUnit 5 + Mockito + MockMvc standalone; Spring Cloud Gateway MVC; Expo / React Native / TypeScript strict.

**Spec:** [`../specs/2026-10-10-investor-borrower-profile-design.md`](../specs/2026-10-10-investor-borrower-profile-design.md)

## Global Constraints

- Không trả: `modelExplanation` (SHAP, kể cả `tom_tat`), `borrowerExplanation`, `rejectionReasons`, `inputSnapshot`, `borrowerId`, họ tên, CCCD, SĐT, email, địa chỉ, lịch sử/ghi chú admin, phiên bản mô hình.
- Chỉ role `ROLE_INVESTOR`; chỉ hồ sơ `fundingStatus != null`; còn lại 404 như không tồn tại.
- Không migration, không đổi event Kafka. Không gọi User/AI/Fineract trong request.
- Loan không tạo cặp `XService`/`XServiceImpl` (rule 03/04): service mới là class cụ thể.
- Comment/JavaDoc tiếng Việt, giải thích lý do. Mobile: TS strict, không `any`, không `!`, không `catch` rỗng; component ≤ 200 dòng, screen ≤ 250 dòng.
- Hồ sơ giả lập (`MOCK_USER_PROFILE`) phải được ghi rõ là giả lập trên UI.
- Không tự `git commit` (luật repo); bước "commit" trong plan thay bằng kiểm tra `git diff --stat`.

---

### Task 1: Loan — tách bộ đọc snapshot chấm điểm

**Files:**
- Create: `finora-loan/src/main/java/com/finora/loan/mapper/scoring/StoredAiCreditResponseReader.java`
- Modify: `finora-loan/src/main/java/com/finora/loan/service/decision/impl/AdminLoanDecisionServiceImpl.java` (bỏ `readSnapshot`, dùng reader)
- Test: `finora-loan/src/test/java/com/finora/loan/mapper/scoring/StoredAiCreditResponseReaderTest.java`

**Interfaces:**
- Produces: `StoredAiCreditResponse StoredAiCreditResponseReader.read(CreditScoringAssessment assessment)` — `null` khi trống hoặc JSON hỏng.

- [ ] **Step 1: Viết test**

```java
package com.finora.loan.mapper.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import org.junit.jupiter.api.Test;

class StoredAiCreditResponseReaderTest {

    private final StoredAiCreditResponseReader reader = new StoredAiCreditResponseReader(new ObjectMapper());

    @Test
    void readsStoredSnapshot() {
        StoredAiCreditResponse snapshot = reader.read(assessmentWith(
                "{\"riskScore\":95,\"ruleTrace\":[{\"ma\":\"R1\",\"diem\":20}]}"));

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.riskScore()).isEqualTo(95);
        assertThat(snapshot.ruleTrace()).hasSize(1);
    }

    @Test
    void blankOrCorruptSnapshotIsTreatedAsMissing() {
        assertThat(reader.read(assessmentWith(null))).isNull();
        assertThat(reader.read(assessmentWith("  "))).isNull();
        assertThat(reader.read(assessmentWith("{not-json"))).isNull();
    }

    private static CreditScoringAssessment assessmentWith(String json) {
        CreditScoringAssessment assessment = mock(CreditScoringAssessment.class);
        when(assessment.getId()).thenReturn(51L);
        when(assessment.getResponseSnapshotJson()).thenReturn(json);
        return assessment;
    }
}
```

- [ ] **Step 2: Chạy, xác nhận FAIL** — `mvn -q -pl finora-loan test -Dtest=StoredAiCreditResponseReaderTest` → lỗi biên dịch `StoredAiCreditResponseReader` chưa có.

- [ ] **Step 3: Viết reader**

```java
package com.finora.loan.mapper.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Đọc lại snapshot response AI đã lưu trong {@code credit_scoring_assessments.response_snapshot_json},
 * dùng chung cho màn thẩm định của admin và hồ sơ người vay của nhà đầu tư.
 *
 * <p>Ánh xạ về {@link StoredAiCreditResponse} thay vì đọc khoá bằng chuỗi: snapshot được ghi bằng
 * chính record đó nên tên trường phải khớp, và nếu record đổi thì lỗi hiện ra lúc biên dịch chứ không
 * phải bằng các giá trị null trên màn hình.</p>
 *
 * <p>Trả {@code null} khi chưa có; JSON hỏng thì coi như không có thay vì ném lỗi 500, vì một bản
 * ghi lỗi không nên chặn cả màn hình đang đọc nó.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StoredAiCreditResponseReader {

    private final ObjectMapper objectMapper;

    public StoredAiCreditResponse read(CreditScoringAssessment assessment) {
        String json = assessment.getResponseSnapshotJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredAiCreditResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Snapshot chấm điểm không đọc được: assessmentId={}", assessment.getId(), e);
            return null;
        }
    }
}
```

Trong `AdminLoanDecisionServiceImpl`: thêm field `private final StoredAiCreditResponseReader snapshotReader;`, đổi `readSnapshot(assessment)` thành `snapshotReader.read(assessment)`, xoá method `readSnapshot` cùng import `JsonProcessingException` (và `ObjectMapper` nếu không còn dùng).

- [ ] **Step 4: Chạy lại** — `mvn -q -pl finora-loan test -Dtest=StoredAiCreditResponseReaderTest` → PASS.

---

### Task 2: Loan — response, mapper allowlist, service, controller

**Files:**
- Create: `finora-loan/src/main/java/com/finora/loan/dto/application/response/InvestorBorrowerProfileResponse.java`
- Create: `finora-loan/src/main/java/com/finora/loan/mapper/application/InvestorBorrowerProfileMapper.java`
- Create: `finora-loan/src/main/java/com/finora/loan/service/application/InvestorBorrowerProfileService.java`
- Create: `finora-loan/src/main/java/com/finora/loan/controller/InvestorLoanApplicationController.java`
- Test: `finora-loan/src/test/java/com/finora/loan/mapper/application/InvestorBorrowerProfileMapperTest.java`
- Test: `finora-loan/src/test/java/com/finora/loan/service/application/InvestorBorrowerProfileServiceTest.java`
- Test: `finora-loan/src/test/java/com/finora/loan/controller/InvestorLoanApplicationControllerTest.java`

**Interfaces:**
- Consumes: `StoredAiCreditResponseReader.read(...)` (Task 1).
- Produces (HTTP, app đọc ở Task 5): JSON đúng như mục 5 của spec; `ruleResults[].value` là số, chuỗi hoặc `null`.

Quyền kiểm ở service bằng `SecurityUtils.hasRole("ROLE_INVESTOR")` và ném `LoanBusinessException.forbidden("INVESTOR_ROLE_REQUIRED", …)` (JSON 403 như `requireAdmin`). Không thêm matcher URL: handler mặc định của Spring trả 403 rỗng, app sẽ hiểu nhầm thành "phiên hết hạn".

- [ ] **Step 1: Viết test mapper** (allowlist bảng luật; lần chấm hỏng thì không có điểm/luật)

```java
package com.finora.loan.mapper.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InvestorBorrowerProfileMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-06T05:10:31Z");
    private final InvestorBorrowerProfileMapper mapper = new InvestorBorrowerProfileMapper();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void copiesOnlyAllowlistedRuleFields() throws Exception {
        var trace = json.readTree("""
                [
                  {"ma":"CHARACTER_CIC_HISTORY","mo_ta":"Điểm tín dụng CIC — lịch sử trả nợ","truong":"cic_score",
                   "gia_tri":712,"diem":15,"toi_da":20,"trong_so":1.0,"thieu_du_lieu":false,"goi_y":"bí mật"},
                  {"ma":"X","mo_ta":"Lồng","truong":"x","gia_tri":{"so_cccd":"0790"},"diem":8,"toi_da":20,
                   "trong_so":2,"thieu_du_lieu":true},
                  "khong-phai-object"
                ]
                """);

        InvestorBorrowerProfileResponse response = mapper.toResponse(
                application(), null, null, null, assessment(CreditAssessmentStatus.SUCCEEDED), trace);

        assertThat(response.ruleResults()).hasSize(2);
        var first = response.ruleResults().getFirst();
        assertThat(first.code()).isEqualTo("CHARACTER_CIC_HISTORY");
        assertThat(first.field()).isEqualTo("cic_score");
        assertThat(first.value().intValue()).isEqualTo(712);
        assertThat(first.points()).isEqualTo(15);
        assertThat(first.maxPoints()).isEqualTo(20);
        assertThat(first.missingData()).isFalse();
        var nested = response.ruleResults().get(1);
        assertThat(nested.value().isNull()).isTrue();
        assertThat(nested.weight()).isEqualByComparingTo("2");
        assertThat(nested.missingData()).isTrue();
        assertThat(json.writeValueAsString(response.ruleResults())).doesNotContain("goi_y", "so_cccd", "bí mật");
    }

    @Test
    void unsuccessfulScoringPublishesNoScoreAndNoRules() throws Exception {
        var trace = json.readTree("[{\"ma\":\"R1\",\"mo_ta\":\"Luật\",\"truong\":\"dti\",\"gia_tri\":5}]");

        InvestorBorrowerProfileResponse response = mapper.toResponse(
                application(), null, null, null, assessment(CreditAssessmentStatus.FAILED), trace);

        assertThat(response.assessment()).isNull();
        assertThat(response.ruleResults()).isEmpty();
        assertThat(response.background().age()).isNull();
        assertThat(response.background().homeOwnership()).isEqualTo(HomeOwnership.RENT);
        assertThat(response.loan().firstInstallment()).isNull();
        assertThat(response.creditHistory()).isNull();
    }

    private static LoanApplication application() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getApplicationNumber()).thenReturn("LA-TEST-001");
        when(application.getPurposeCode()).thenReturn(LoanPurpose.EDUCATION);
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("30000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(12);
        when(application.getFinancialSnapshot()).thenReturn(ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY, HomeOwnership.RENT,
                new BigDecimal("3000000"), NOW));
        return application;
    }

    private static CreditScoringAssessment assessment(CreditAssessmentStatus status) {
        CreditScoringAssessment assessment = mock(CreditScoringAssessment.class);
        when(assessment.getStatus()).thenReturn(status);
        when(assessment.getEvaluationScore()).thenReturn(new BigDecimal("68.92"));
        return assessment;
    }
}
```

- [ ] **Step 2: Viết test service** (quyền, 404, snapshot hỏng, lịch dự phòng, không lộ SHAP)

```java
package com.finora.loan.service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanDecisionSource;
import com.finora.loan.domain.application.LoanFundingStatus;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditProfileSource;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.mapper.application.InvestorBorrowerProfileMapper;
import com.finora.loan.mapper.scoring.StoredAiCreditResponseReader;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Hồ sơ người vay cho nhà đầu tư: đúng người được xem, đúng hồ sơ được xem, và không bao giờ
 * mang theo SHAP hay định danh dù snapshot chấm điểm có đủ các khối đó.
 */
class InvestorBorrowerProfileServiceTest {

    private static final String NUMBER = "LA-TEST-001";
    private static final Instant NOW = Instant.parse("2026-10-06T05:10:31Z");
    private static final String SNAPSHOT = """
            {"pdProbability":0.3568,"riskScore":95,"evaluationScore":68.92,"creditGrade":"C",
             "recommendation":"PENDING_REVIEW",
             "borrowerExplanation":{"thong_diep":"Hồ sơ của bạn","ly_do_chinh":[],"goi_y_cai_thien":["Giảm nợ"]},
             "modelExplanation":{"gia_tri_co_so":-1.2,"canh_bao":[],"yeu_to_bat_loi":[{"dac_trung":"int_rate"}],
                                 "yeu_to_co_loi":[],"tom_tat":{"bat_loi":[{"ma_nhom":"lai_suat"}],"co_loi":[]}},
             "ruleTrace":[{"ma":"CHARACTER_CIC_HISTORY","mo_ta":"Điểm tín dụng CIC — lịch sử trả nợ",
                           "truong":"cic_score","gia_tri":712,"diem":15,"toi_da":20,"trong_so":1.0,
                           "thieu_du_lieu":false}],
             "rejectionReasons":["CIC_CURRENT_BAD_DEBT"],"modelVersion":"17.0.0","decisionPolicyVersion":"CREDIT_POLICY_V1"}
            """;

    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final ScheduleCalculationSnapshotRepository schedules = mock(ScheduleCalculationSnapshotRepository.class);
    private final BorrowerEligibilityCheckRepository eligibilities = mock(BorrowerEligibilityCheckRepository.class);
    private final BorrowerCreditProfileRepository creditProfiles = mock(BorrowerCreditProfileRepository.class);
    private final CreditScoringAssessmentRepository assessments = mock(CreditScoringAssessmentRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final InvestorBorrowerProfileService service = new InvestorBorrowerProfileService(
            applications, schedules, eligibilities, creditProfiles, assessments,
            new StoredAiCreditResponseReader(objectMapper), new InvestorBorrowerProfileMapper());

    @BeforeEach
    void signInAsInvestor() {
        authenticateAs("ROLE_INVESTOR");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listedApplicationReturnsAnonymousProfileWithoutShap() throws Exception {
        LoanApplication application = listedApplication();
        stubEvidence(application, SNAPSHOT, true);

        InvestorBorrowerProfileResponse response = service.profile(NUMBER);

        assertThat(response.applicationNumber()).isEqualTo(NUMBER);
        assertThat(response.loan().purposeLabel()).isEqualTo("Chi phí giáo dục");
        assertThat(response.loan().maximumInstallment()).isEqualByComparingTo("2730000.00");
        assertThat(response.capacity().dtiSnapshot()).isEqualByComparingTo("15.00");
        assertThat(response.background().age()).isEqualTo(30);
        assertThat(response.background().profileSource()).isEqualTo(BorrowerProfileSource.MOCK_USER_PROFILE);
        assertThat(response.creditHistory().source()).isEqualTo(CreditProfileSource.NO_HISTORY);
        assertThat(response.assessment().creditGrade()).isEqualTo("C");
        assertThat(response.assessment().decisionSource()).isEqualTo(LoanDecisionSource.ADMIN);
        assertThat(response.ruleResults()).singleElement()
                .satisfies(rule -> assertThat(rule.value().intValue()).isEqualTo(712));

        String body = objectMapper.writeValueAsString(response);
        assertThat(body).doesNotContain(
                "modelExplanation", "tom_tat", "yeu_to", "int_rate", "borrowerExplanation", "thong_diep",
                "goi_y_cai_thien", "rejectionReasons", "CIC_CURRENT_BAD_DEBT", "borrowerId", "BORROWER-42",
                "modelVersion");
    }

    @Test
    void applicationNotYetOnMarketLooksLikeMissing() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getFundingStatus()).thenReturn(null);
        when(applications.findByApplicationNumber(NUMBER)).thenReturn(Optional.of(application));

        assertThatThrownBy(() -> service.profile(NUMBER)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(schedules, eligibilities, creditProfiles, assessments);
    }

    @Test
    void unknownApplicationIsNotFound() {
        when(applications.findByApplicationNumber(NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.profile(NUMBER)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void borrowerCannotReadAnotherBorrowersProfile() {
        authenticateAs("ROLE_BORROWER");

        assertThatThrownBy(() -> service.profile(NUMBER))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((BusinessException) error).getCode()).isEqualTo("INVESTOR_ROLE_REQUIRED");
                });
        verifyNoInteractions(applications);
    }

    @Test
    void corruptSnapshotKeepsProfileButDropsRules() {
        LoanApplication application = listedApplication();
        stubEvidence(application, "{hỏng", true);

        InvestorBorrowerProfileResponse response = service.profile(NUMBER);

        assertThat(response.ruleResults()).isEmpty();
        assertThat(response.assessment().evaluationScore()).isEqualByComparingTo("68.92");
    }

    @Test
    void fallsBackToSubmissionScheduleWhenContractScheduleMissing() {
        LoanApplication application = listedApplication();
        stubEvidence(application, SNAPSHOT, false);

        InvestorBorrowerProfileResponse response = service.profile(NUMBER);

        assertThat(response.loan().firstInstallment()).isEqualByComparingTo("2500000.00");
    }

    private LoanApplication listedApplication() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(7L);
        when(application.getApplicationNumber()).thenReturn(NUMBER);
        when(application.getBorrowerId()).thenReturn("BORROWER-42");
        when(application.getFundingStatus()).thenReturn(LoanFundingStatus.REQUESTED);
        when(application.getPurposeCode()).thenReturn(LoanPurpose.EDUCATION);
        when(application.getPurposeDetail()).thenReturn("Học phí học kỳ I");
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("30000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(12);
        when(application.getRepaymentMethodSnapshot()).thenReturn(RepaymentMethod.ANNUITY);
        when(application.getFinalAnnualInterestRate()).thenReturn(new BigDecimal("15.5000"));
        when(application.getDecisionSource()).thenReturn(LoanDecisionSource.ADMIN);
        when(application.getLatestCreditAssessmentId()).thenReturn(51L);
        when(application.getFinancialSnapshot()).thenReturn(ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY, HomeOwnership.RENT,
                new BigDecimal("3000000"), NOW));
        when(applications.findByApplicationNumber(NUMBER)).thenReturn(Optional.of(application));
        return application;
    }

    private void stubEvidence(LoanApplication application, String snapshotJson, boolean hasContractSchedule) {
        ScheduleCalculationSnapshot contract = schedule("2730000.00");
        ScheduleCalculationSnapshot submission = schedule("2500000.00");
        when(schedules.findByApplicationIdAndPurpose(7L, ScheduleCalculationPurpose.CONTRACT))
                .thenReturn(hasContractSchedule ? Optional.of(contract) : Optional.empty());
        when(schedules.findByApplicationIdAndPurpose(7L, ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .thenReturn(Optional.of(submission));

        BorrowerEligibilityCheck eligibility = mock(BorrowerEligibilityCheck.class);
        when(eligibility.getAge()).thenReturn(30);
        when(eligibility.getKycStatus()).thenReturn(BorrowerKycStatus.VERIFIED);
        when(eligibility.getProfileSource()).thenReturn(BorrowerProfileSource.MOCK_USER_PROFILE);
        when(eligibilities.findFirstByApplicationIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.of(eligibility));

        BorrowerCreditProfile creditProfile = mock(BorrowerCreditProfile.class);
        when(creditProfile.getSource()).thenReturn(CreditProfileSource.NO_HISTORY);
        when(creditProfiles.findByBorrowerId(application.getBorrowerId())).thenReturn(Optional.of(creditProfile));

        CreditScoringAssessment assessment = mock(CreditScoringAssessment.class);
        when(assessment.getId()).thenReturn(51L);
        when(assessment.getStatus()).thenReturn(CreditAssessmentStatus.SUCCEEDED);
        when(assessment.getEvaluationScore()).thenReturn(new BigDecimal("68.92"));
        when(assessment.getCreditGrade()).thenReturn("C");
        when(assessment.getPdProbability()).thenReturn(new BigDecimal("0.3568"));
        when(assessment.getRiskScore()).thenReturn(95);
        when(assessment.getResponseSnapshotJson()).thenReturn(snapshotJson);
        when(assessments.findByIdAndApplicationId(51L, 7L)).thenReturn(Optional.of(assessment));
    }

    private static ScheduleCalculationSnapshot schedule(String installment) {
        ScheduleCalculationSnapshot schedule = mock(ScheduleCalculationSnapshot.class);
        when(schedule.getFirstInstallment()).thenReturn(new BigDecimal(installment));
        when(schedule.getMaximumInstallment()).thenReturn(new BigDecimal(installment));
        when(schedule.getTotalRepayment()).thenReturn(new BigDecimal("32760000.00"));
        when(schedule.getExpectedDisbursementDate()).thenReturn(LocalDate.of(2026, 10, 20));
        return schedule;
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, role));
    }
}
```

- [ ] **Step 3: Viết test controller** (tên field JSON, 403 dạng JSON)

```java
package com.finora.loan.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.IntNode;
import com.finora.common.exception.GlobalExceptionHandler;
import com.finora.loan.domain.application.CreditInformationSource;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanDecisionSource;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.Background;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.CreditAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.LoanRequest;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RepaymentCapacity;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RuleResult;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.service.application.InvestorBorrowerProfileService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Hợp đồng JSON mà app nhà đầu tư đọc: tên field, kiểu giá trị luật và lỗi 403 có mã. */
class InvestorLoanApplicationControllerTest {

    private static final String PATH = "/api/v1/investor/loan-applications/{applicationNumber}/borrower-profile";
    private final InvestorBorrowerProfileService service = mock(InvestorBorrowerProfileService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MappingJackson2HttpMessageConverter json = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        mockMvc = MockMvcBuilders.standaloneSetup(new InvestorLoanApplicationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(json)
                .build();
    }

    @Test
    void exposesFieldNamesTheAppReads() throws Exception {
        when(service.profile("LA-TEST-001")).thenReturn(response());

        mockMvc.perform(get(PATH, "LA-TEST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationNumber").value("LA-TEST-001"))
                .andExpect(jsonPath("$.loan.purposeLabel").value("Chi phí giáo dục"))
                .andExpect(jsonPath("$.loan.expectedDisbursementDate").value("2026-10-20"))
                .andExpect(jsonPath("$.capacity.dtiSnapshot").value(15.0))
                .andExpect(jsonPath("$.background.profileSource").value("MOCK_USER_PROFILE"))
                .andExpect(jsonPath("$.assessment.pdProbability").value(0.3568))
                .andExpect(jsonPath("$.ruleResults[0].field").value("cic_score"))
                .andExpect(jsonPath("$.ruleResults[0].value").value(712))
                .andExpect(jsonPath("$.borrowerId").doesNotExist())
                .andExpect(jsonPath("$.modelExplanation").doesNotExist());
    }

    @Test
    void forbiddenIsJsonWithCode() throws Exception {
        when(service.profile("LA-TEST-001")).thenThrow(LoanBusinessException.forbidden(
                "INVESTOR_ROLE_REQUIRED", "Chỉ tài khoản nhà đầu tư mới xem được hồ sơ người vay"));

        mockMvc.perform(get(PATH, "LA-TEST-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVESTOR_ROLE_REQUIRED"));
    }

    private static InvestorBorrowerProfileResponse response() {
        Instant at = Instant.parse("2026-10-06T05:10:31Z");
        return new InvestorBorrowerProfileResponse(
                "LA-TEST-001",
                new LoanRequest(LoanPurpose.EDUCATION, "Chi phí giáo dục", "Học phí", new BigDecimal("30000000.00"),
                        12, RepaymentMethod.ANNUITY, new BigDecimal("15.5000"), new BigDecimal("2730000.00"),
                        new BigDecimal("2730000.00"), new BigDecimal("32760000.00"), LocalDate.of(2026, 10, 20)),
                new RepaymentCapacity(new BigDecimal("20000000.00"), new BigDecimal("3000000.00"),
                        new BigDecimal("15.00"), 60, CreditInformationSource.SELF_DECLARED, at),
                new Background(30, BorrowerKycStatus.VERIFIED, BorrowerProfileSource.MOCK_USER_PROFILE, at,
                        HomeOwnership.RENT, EducationLevel.UNIVERSITY),
                null,
                new CreditAssessment(new BigDecimal("68.92"), "C", new BigDecimal("0.3568"), 95,
                        LoanDecisionSource.ADMIN, at),
                List.of(new RuleResult("CHARACTER_CIC_HISTORY", "Điểm tín dụng CIC — lịch sử trả nợ", "cic_score",
                        IntNode.valueOf(712), 15, 20, new BigDecimal("1.0"), false)));
    }
}
```

- [ ] **Step 4: Chạy, xác nhận FAIL** — `mvn -q -pl finora-loan test -Dtest='InvestorBorrowerProfile*Test,InvestorLoanApplicationControllerTest'` → lỗi biên dịch (class chưa có).

- [ ] **Step 5: Viết response DTO**

```java
package com.finora.loan.dto.application.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.domain.application.CreditInformationSource;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanDecisionSource;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.CreditProfileSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Hồ sơ người vay nhà đầu tư xem trước khi góp vốn: những gì admin thấy ở trang thẩm định, trừ định
 * danh và trừ giải thích SHAP.
 *
 * <p>Cố ý không có {@code borrowerId}, họ tên, CCCD, liên hệ, địa chỉ (NĐ 94/2025 Điều 11 khoản 2
 * điểm d: bảo mật với bên không liên quan; Luật BVDLCN 2025: tối thiểu hóa). Cũng không có
 * {@code modelExplanation} (SHAP) và {@code borrowerExplanation}: SHAP chỉ dành cho thẩm định viên,
 * còn thông điệp gửi người vay viết cho chính người vay và phần gợi ý xếp theo SHAP.</p>
 *
 * <p>Tiền và tỷ lệ là số JSON như mọi DTO khác của Loan; {@code dtiSnapshot} đã là phần trăm,
 * {@code pdProbability} là tỷ lệ 0..1.</p>
 */
public record InvestorBorrowerProfileResponse(
        String applicationNumber,
        LoanRequest loan,
        RepaymentCapacity capacity,
        Background background,
        /** {@code null} khi Loan chưa có bản ghi lịch sử vay nội bộ của người vay. */
        CreditHistory creditHistory,
        /** {@code null} khi lần chấm gần nhất chưa thành công. */
        CreditAssessment assessment,
        List<RuleResult> ruleResults
) {
    /** Khoản vay người vay đề nghị; kỳ trả lấy từ lịch cuối, chưa có thì lịch lúc nộp. */
    public record LoanRequest(
            LoanPurpose purposeCode,
            String purposeLabel,
            String purposeDetail,
            BigDecimal requestedAmount,
            Integer requestedTermMonths,
            RepaymentMethod repaymentMethod,
            BigDecimal finalAnnualInterestRate,
            BigDecimal firstInstallment,
            BigDecimal maximumInstallment,
            BigDecimal totalRepayment,
            LocalDate expectedDisbursementDate
    ) {
    }

    /** Số liệu người vay tự khai lúc nộp hồ sơ. */
    public record RepaymentCapacity(
            BigDecimal declaredMonthlyIncome,
            BigDecimal monthlyDebtObligations,
            BigDecimal dtiSnapshot,
            Integer employmentLengthMonths,
            CreditInformationSource informationSource,
            Instant capturedAt
    ) {
    }

    /**
     * Nhân thân không định danh. Tuổi và eKYC lấy từ lần kiểm tra điều kiện vay; {@code profileSource}
     * cho biết đó là hồ sơ thật từ User Service hay hồ sơ giả lập của môi trường thử nghiệm.
     */
    public record Background(
            Integer age,
            BorrowerKycStatus kycStatus,
            BorrowerProfileSource profileSource,
            Instant checkedAt,
            HomeOwnership homeOwnership,
            EducationLevel educationLevel
    ) {
    }

    /** Lịch sử vay tại FINORA (không phải CIC). */
    public record CreditHistory(
            boolean hasInternalCreditHistory,
            int completedLoanCount,
            int internalDelinquenciesLast2Years,
            int internalDefaultedLoanCount,
            CreditProfileSource source
    ) {
    }

    /** Kết quả chấm điểm; {@code riskScore} là điểm theo bảng luật 0..100. */
    public record CreditAssessment(
            BigDecimal evaluationScore,
            String creditGrade,
            BigDecimal pdProbability,
            Integer riskScore,
            LoanDecisionSource decisionSource,
            Instant scoredAt
    ) {
    }

    /** Một luật đã chấm, chép theo allowlist từ {@code rule_trace} của AI; {@code value} là số, chuỗi hoặc null. */
    public record RuleResult(
            String code,
            String description,
            String field,
            JsonNode value,
            Integer points,
            Integer maxPoints,
            BigDecimal weight,
            boolean missingData
    ) {
    }
}
```

- [ ] **Step 6: Viết mapper**

```java
package com.finora.loan.mapper.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.Background;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.CreditAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.CreditHistory;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.LoanRequest;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RepaymentCapacity;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RuleResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Dựng hồ sơ người vay cho nhà đầu tư từ các snapshot Loan đã lưu. Mapper chỉ chép theo allowlist:
 * field nào không có ở đây thì không bao giờ ra khỏi Loan qua API này.
 */
@Component
public class InvestorBorrowerProfileMapper {

    public InvestorBorrowerProfileResponse toResponse(
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            BorrowerEligibilityCheck eligibility,
            BorrowerCreditProfile creditProfile,
            CreditScoringAssessment assessment,
            JsonNode ruleTrace
    ) {
        ApplicantFinancialSnapshot financial = application.getFinancialSnapshot();
        boolean scored = assessment != null && assessment.getStatus() == CreditAssessmentStatus.SUCCEEDED;
        return new InvestorBorrowerProfileResponse(
                application.getApplicationNumber(),
                loan(application, schedule),
                new RepaymentCapacity(
                        financial.getDeclaredMonthlyIncome(), financial.getMonthlyDebtObligations(),
                        financial.getDtiSnapshot(), financial.getEmploymentLengthMonths(),
                        financial.getInformationSource(), financial.getCapturedAt()),
                new Background(
                        eligibility == null ? null : eligibility.getAge(),
                        eligibility == null ? null : eligibility.getKycStatus(),
                        eligibility == null ? null : eligibility.getProfileSource(),
                        eligibility == null ? null : eligibility.getCheckedAt(),
                        financial.getHomeOwnership(), financial.getEducationLevel()),
                creditHistory(creditProfile),
                // Lần chấm lỗi/đang chờ thì không công bố con số hay luật nào, kể cả khi còn snapshot cũ.
                scored ? assessment(application, assessment) : null,
                scored ? ruleResults(ruleTrace) : List.of()
        );
    }

    private LoanRequest loan(LoanApplication application, ScheduleCalculationSnapshot schedule) {
        return new LoanRequest(
                application.getPurposeCode(),
                application.getPurposeCode().getLabel(),
                application.getPurposeDetail(),
                application.getRequestedAmount(),
                application.getRequestedTermMonths(),
                application.getRepaymentMethodSnapshot(),
                application.getFinalAnnualInterestRate(),
                schedule == null ? null : schedule.getFirstInstallment(),
                schedule == null ? null : schedule.getMaximumInstallment(),
                schedule == null ? null : schedule.getTotalRepayment(),
                schedule == null ? null : schedule.getExpectedDisbursementDate()
        );
    }

    private CreditHistory creditHistory(BorrowerCreditProfile profile) {
        if (profile == null) {
            return null;
        }
        return new CreditHistory(
                profile.isHasInternalCreditHistory(), profile.getCompletedLoanCount(),
                profile.getInternalDelinquenciesLast2Years(), profile.getInternalDefaultedLoanCount(),
                profile.getSource());
    }

    private CreditAssessment assessment(LoanApplication application, CreditScoringAssessment assessment) {
        return new CreditAssessment(
                assessment.getEvaluationScore(), assessment.getCreditGrade(), assessment.getPdProbability(),
                assessment.getRiskScore(), application.getDecisionSource(), assessment.getScoredAt());
    }

    /**
     * Chép {@code rule_trace} theo allowlist. Luật là dữ liệu admin cấu hình nên phần tử lạ (không phải
     * object) bị bỏ qua; {@code gia_tri} không phải số/chuỗi thành null để không lọt cấu trúc lồng.
     */
    private List<RuleResult> ruleResults(JsonNode ruleTrace) {
        if (ruleTrace == null || !ruleTrace.isArray()) {
            return List.of();
        }
        List<RuleResult> results = new ArrayList<>();
        for (JsonNode item : ruleTrace) {
            if (!item.isObject()) {
                continue;
            }
            results.add(new RuleResult(
                    text(item, "ma"), text(item, "mo_ta"), text(item, "truong"), scalar(item.get("gia_tri")),
                    integer(item, "diem"), integer(item, "toi_da"), decimal(item, "trong_so"),
                    item.path("thieu_du_lieu").asBoolean(false)));
        }
        return List.copyOf(results);
    }

    private static String text(JsonNode item, String key) {
        JsonNode value = item.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Integer integer(JsonNode item, String key) {
        JsonNode value = item.get(key);
        return value != null && value.isNumber() ? value.intValue() : null;
    }

    private static BigDecimal decimal(JsonNode item, String key) {
        JsonNode value = item.get(key);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static JsonNode scalar(JsonNode value) {
        return value != null && (value.isNumber() || value.isTextual()) ? value : NullNode.getInstance();
    }
}
```

- [ ] **Step 7: Viết service**

```java
package com.finora.loan.service.application;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import com.finora.loan.mapper.application.InvestorBorrowerProfileMapper;
import com.finora.loan.mapper.scoring.StoredAiCreditResponseReader;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hồ sơ người vay cho nhà đầu tư trước khi góp vốn (NĐ 94/2025 Điều 22 khoản 3 và khoản 8 điểm c:
 * cung cấp đầy đủ thông tin khoản vay trước khi khách hàng giao kết).
 *
 * <p>Chỉ hồ sơ đã được đưa lên sàn ({@code fundingStatus != null}) mới xem được. Hồ sơ chưa lên sàn trả
 * cùng lỗi 404 như hồ sơ không tồn tại, để không dò được hồ sơ đang thẩm định. Hồ sơ đã đủ vốn vẫn xem
 * được vì Note của nó còn được mua bán trên chợ thứ cấp.</p>
 *
 * <p>Số query cố định (tối đa 6), chỉ đọc snapshot local; không gọi User, AI hay Fineract.</p>
 */
@Service
@RequiredArgsConstructor
public class InvestorBorrowerProfileService {

    private static final String INVESTOR_ROLE = "ROLE_INVESTOR";

    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final BorrowerEligibilityCheckRepository eligibilityRepository;
    private final BorrowerCreditProfileRepository creditProfileRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final StoredAiCreditResponseReader snapshotReader;
    private final InvestorBorrowerProfileMapper mapper;

    @Transactional(readOnly = true)
    public InvestorBorrowerProfileResponse profile(String applicationNumber) {
        // Kiểm ở service (không chặn theo URL) để người không đủ quyền nhận lỗi JSON có mã, giống
        // requireAdmin; handler mặc định của Spring trả 403 rỗng, app sẽ đọc nhầm thành hết phiên.
        if (!SecurityUtils.hasRole(INVESTOR_ROLE)) {
            throw LoanBusinessException.forbidden(
                    "INVESTOR_ROLE_REQUIRED", "Chỉ tài khoản nhà đầu tư mới xem được hồ sơ người vay");
        }
        LoanApplication application = applicationRepository.findByApplicationNumber(applicationNumber)
                .filter(found -> found.getFundingStatus() != null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy hồ sơ người vay của khoản vay này trên sàn"));

        // Lịch cuối (CONTRACT) là điều khoản người vay đã được phép gọi vốn; hồ sơ cũ chưa có thì
        // dùng lịch lúc nộp để vẫn có kỳ trả tham khảo.
        ScheduleCalculationSnapshot schedule = scheduleRepository
                .findByApplicationIdAndPurpose(application.getId(), ScheduleCalculationPurpose.CONTRACT)
                .or(() -> scheduleRepository.findByApplicationIdAndPurpose(
                        application.getId(), ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .orElse(null);
        BorrowerEligibilityCheck eligibility = eligibilityRepository
                .findFirstByApplicationIdOrderByCreatedAtDescIdDesc(application.getId())
                .orElse(null);
        BorrowerCreditProfile creditProfile = creditProfileRepository
                .findByBorrowerId(application.getBorrowerId())
                .orElse(null);
        CreditScoringAssessment assessment = application.getLatestCreditAssessmentId() == null
                ? null
                : assessmentRepository.findByIdAndApplicationId(
                        application.getLatestCreditAssessmentId(), application.getId()).orElse(null);
        StoredAiCreditResponse snapshot = assessment == null ? null : snapshotReader.read(assessment);

        return mapper.toResponse(application, schedule, eligibility, creditProfile, assessment,
                snapshot == null ? null : snapshot.ruleTrace());
    }
}
```

- [ ] **Step 8: Viết controller**

```java
package com.finora.loan.controller;

import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.service.application.InvestorBorrowerProfileService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hồ sơ vay nhìn từ phía nhà đầu tư: chỉ đọc, chỉ khoản đã lên sàn, ẩn danh người vay. */
@RestController
@RequestMapping("/api/v1/investor/loan-applications")
@RequiredArgsConstructor
@Validated
public class InvestorLoanApplicationController {

    private final InvestorBorrowerProfileService service;

    @GetMapping("/{applicationNumber}/borrower-profile")
    public InvestorBorrowerProfileResponse borrowerProfile(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber
    ) {
        return service.profile(applicationNumber);
    }
}
```

- [ ] **Step 9: Chạy test Loan** — `mvn -q -pl finora-loan test -Dtest='StoredAiCreditResponseReaderTest,InvestorBorrowerProfile*Test,InvestorLoanApplicationControllerTest,AdminCreditScoringControllerTest,StoredAiCreditResponseTest'` → PASS; rồi `mvn -q -pl finora-loan test` (toàn bộ unit test) → PASS.

---

### Task 3: Investment — `applicationNumber` trong listing; Gateway route

**Files:**
- Modify: `finora-investment/src/main/java/com/finora/investment/dto/response/MarketListingResponse.java` (thêm `String applicationNumber` sau `loanId`)
- Modify: `finora-investment/src/main/java/com/finora/investment/mapper/InvestmentMapper.java:26-46`
- Test: `finora-investment/src/test/java/com/finora/investment/mapper/InvestmentMapperTest.java`
- Modify: `finora-gateway/src/main/resources/application.yml:11`, `finora-gateway/src/main/resources/application-docker.yml:18` (thêm `,/api/v1/investor/loan-applications/**` vào route `loan-service`)

- [ ] **Step 1: Viết test**

```java
package com.finora.investment.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.dto.response.MarketListingResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InvestmentMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");

    /** App dùng mã hồ sơ này để tải hồ sơ người vay từ Loan; thiếu nó thì thẻ hồ sơ không hiện. */
    @Test
    void listingResponseCarriesApplicationNumber() {
        MarketListing listing = MarketListing.builder()
                .id(5L).loanId(77L).applicationNumber("LA-0123456789ABCDEF0123")
                .productCode("SP-01").purpose("Chi phí giáo dục").region("Không công bố")
                .creditGrade("B").creditScore(80)
                .targetAmount(new BigDecimal("10000000.00")).committedAmount(new BigDecimal("4000000.00"))
                .annualInterestRate(new BigDecimal("15.0000")).termMonths(12).repaymentMethod("ANNUITY")
                .noteDenomination(new BigDecimal("1000000.00")).minInvestmentAmount(new BigDecimal("1000000.00"))
                .status(ListingStatus.OPEN).fundingRound(1)
                .fundingOpenedAt(NOW).fundingClosesAt(NOW.plusSeconds(86_400))
                .build();

        MarketListingResponse response = new InvestmentMapper().toListingResponse(listing);

        assertThat(response.applicationNumber()).isEqualTo("LA-0123456789ABCDEF0123");
        assertThat(response.loanId()).isEqualTo(77L);
        assertThat(response.remainingAmount()).isEqualTo("6000000.00");
    }
}
```

- [ ] **Step 2: Chạy, FAIL** — `mvn -q -pl finora-investment test -Dtest=InvestmentMapperTest` → `applicationNumber()` chưa có.

- [ ] **Step 3: Thêm field** — trong `MarketListingResponse` sau `Long loanId,` thêm
  `/** Mã hồ sơ vay (public ID của Loan) để app tải hồ sơ người vay ẩn danh từ Loan; không phải PII. */ String applicationNumber,`;
  trong `InvestmentMapper.toListingResponse` sau `listing.getLoanId(),` thêm `listing.getApplicationNumber(),`.
  Gateway: nối `,/api/v1/investor/loan-applications/**` vào cuối `Path=` của route `loan-service` ở cả hai file yml.

- [ ] **Step 4: Chạy lại** — `mvn -q -pl finora-investment test -Dtest=InvestmentMapperTest` → PASS; `mvn -q -pl finora-gateway -am compile` → BUILD SUCCESS.

---

### Task 4: Mobile — contract, mapper, mock, hook

**Files:**
- Modify: `finora-mobile/src/types/invest.ts` (thêm `applicationNumber` vào `MarketLoan`; thêm `BorrowerProfile`, `BorrowerRuleResult`, `BorrowerKycStatus`, `LoanDecisionSource`)
- Modify: `finora-mobile/src/features/market/mapper.ts` (DTO + map `applicationNumber`)
- Create: `finora-mobile/src/features/market/borrowerMapper.ts`
- Create: `finora-mobile/src/features/market/borrowerDisplay.ts`
- Modify: `finora-mobile/src/features/market/api.ts` (`getBorrowerProfile`)
- Modify: `finora-mobile/src/features/market/hook/useMarket.ts` (`useBorrowerProfile`)
- Modify: `finora-mobile/src/lib/mocks/fixtures.ts`, `finora-mobile/src/lib/mocks/invest.ts`

**Interfaces:**
- Produces: `getBorrowerProfile(applicationNumber: string): Promise<BorrowerProfile>`, `useBorrowerProfile(applicationNumber: string): AsyncState<BorrowerProfile>`,
  `formatRuleValue(field: string, value: number | string): string`, `splitRuleDescription(text: string): { title: string; hint: string | null }`,
  `ruleValue(rules, field): number | string | null | undefined`, `formatScore(n)`, `formatEmployment(months)`, nhãn `HOME_OWNERSHIP_LABEL`, `EDUCATION_LABEL`, `KYC_LABEL`, `DECISION_SOURCE_LABEL`.

Code đầy đủ: xem các file cùng tên trong change set (viết đúng như spec mục 5–6; enum lạ rơi về `UNKNOWN`, `pdProbability × 100` chỉ để hiển thị, đơn vị trường luật chép từ `finora-ai/app/services/credit/truong_du_lieu.py`).

- [ ] **Step 1:** Thêm type + mapper + display + api + hook + mock.
- [ ] **Step 2:** `npx tsc --noEmit` trong `finora-mobile` → exit 0.

---

### Task 5: Mobile — thẻ tóm tắt, màn hồ sơ, điều hướng

**Files:**
- Create: `finora-mobile/src/features/market/component/InfoGrid.tsx` (lưới 2 cột kẻ mảnh, tách từ `LoanTermsCard`)
- Modify: `finora-mobile/src/features/market/component/LoanTermsCard.tsx` (dùng `InfoGrid`)
- Create: `finora-mobile/src/features/market/component/BorrowerSummaryCard.tsx`
- Create: `finora-mobile/src/features/market/component/ProfileCard.tsx` (`ProfileCard`, `ProfileRow`, `ProfileFootnote`)
- Create: `finora-mobile/src/features/market/component/BorrowerScoreCard.tsx`
- Create: `finora-mobile/src/features/market/component/BorrowerFactCards.tsx` (`CapacityCard`, `CreditHistoryCard`, `BackgroundCard`, `LoanRequestCard`)
- Create: `finora-mobile/src/features/market/component/BorrowerRulesCard.tsx`
- Create: `finora-mobile/src/features/market/component/BorrowerProfileScreen.tsx`
- Modify: `finora-mobile/src/features/market/component/LoanDetailScreen.tsx` (chèn thẻ sau `LoanTermsCard`, chỉ khi role INVESTOR và có `applicationNumber`)
- Modify: `finora-mobile/src/features/market/index.tsx`, `finora-mobile/src/navigation/types.ts`, `finora-mobile/src/navigation/MainTabs.tsx`

- [ ] **Step 1:** Viết component + đăng ký route `BorrowerProfile: { applicationNumber: string }`.
- [ ] **Step 2:** `npx tsc --noEmit` → exit 0.
- [ ] **Step 3:** Export web với mock `market` + server giả, chụp màn khoản vay và màn hồ sơ ở 393pt và 360pt; kiểm trạng thái tải/lỗi.

---

### Task 6: Tài liệu + chạy thật

**Files:**
- Modify: `docs/LEGAL-COMPLIANCE.md` (control `LEGAL-DISCLOSURE-02`, nguồn Điều 22, dòng ma trận)
- Modify: `docs/integrations/LOAN-INVESTMENT-EVENTS.md` (mục 2: hồ sơ người vay qua REST Loan)
- Modify: `.agents/rules/08-cross-service-flows.md` (F03: một dòng về hồ sơ người vay)
- Modify: `finora-mobile/PLAN.md` (dòng `MOBILE-INVESTOR-002`)

- [ ] **Step 1:** Cập nhật tài liệu.
- [ ] **Step 2:** `cd docker && docker compose -f docker-compose.app.yml build loan investment gateway`, rồi `docker compose -f docker-compose.app.yml up -d --no-build` (không `--build` để khỏi build lại Fineract).
- [ ] **Step 3:** Đăng nhập `investor@finora.vn`, gọi `GET /api/v1/market/listings?status=OPEN` lấy `applicationNumber`, gọi `GET /api/v1/investor/loan-applications/{n}/borrower-profile` qua Gateway 8080 → 200, không có `modelExplanation`/`borrowerId`; đăng nhập borrower → 403 `INVESTOR_ROLE_REQUIRED`; hồ sơ chưa lên sàn → 404.
