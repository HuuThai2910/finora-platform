package com.finora.loan.service.contract.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.service.contract.ContractDocumentRenderer;
import com.finora.loan.service.contract.ContractHeading;
import com.finora.loan.service.contract.FundedContractAllocation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Render văn bản dễ đọc nhưng vẫn xác định để cùng điều khoản luôn sinh cùng bytes UTF-8 và SHA-256.
 *
 * <p>Lịch trả nợ đọc từ snapshot JSON của Fineract; điều 4 lấy từ {@link LoanServicingContractTerms} theo cách tính
 * lãi của hồ sơ.</p>
 */
@Component
public class ContractDocumentRendererImpl implements ContractDocumentRenderer {

    static final String NOT_UPDATED_PROFILE = "Chưa cập nhật hồ sơ";
    static final String NOT_UPDATED = "Chưa cập nhật";

    private final ObjectMapper objectMapper;

    public ContractDocumentRendererImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String render(
            ContractHeading heading,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            String documentVersion,
            Instant expiresAt
    ) {
        StringBuilder document = new StringBuilder(4096);
        line(document, "HỢP ĐỒNG VAY FINORA");
        blankLine(document);
        line(document, "1. THÔNG TIN HỢP ĐỒNG");
        line(document, "Mã hợp đồng: " + heading.contractLabel());
        line(document, "Mã hồ sơ vay: " + heading.applicationLabel(application));
        if (heading.legacy()) {
            line(document, "Mã tham chiếu người vay: " + application.getBorrowerId());
        } else {
            // Danh tính chụp từ User lúc lập hợp đồng; chưa có thì ghi rõ, không điền tên thay người vay.
            BorrowerIdentity borrower = heading.borrower();
            line(document, "Bên vay: " + (borrower.fullName() == null ? NOT_UPDATED_PROFILE : borrower.fullName()));
            line(document, "Số CCCD bên vay: "
                    + (borrower.maskedIdNumber() == null ? NOT_UPDATED : borrower.maskedIdNumber()));
            line(document, "Mã tra cứu hệ thống: " + heading.contractNumber() + " / "
                    + application.getApplicationNumber());
        }
        blankLine(document);
        line(document, "2. ĐIỀU KHOẢN KHOẢN VAY");
        line(document, "Số tiền vay: " + moneyText(application.getRequestedAmount()));
        line(document, "Thời hạn vay: " + application.getRequestedTermMonths() + " tháng");
        line(document, "Lãi suất cơ sở đã công bố: " + percentage(application.getAnnualInterestRateSnapshot()) + "%/năm");
        BigDecimal finalRate = application.getFinalAnnualInterestRate() == null
                ? application.getAnnualInterestRateSnapshot()
                : application.getFinalAnnualInterestRate();
        line(document, "Lãi suất áp dụng cuối: " + percentage(finalRate) + "%/năm");
        line(document, "Phương thức trả nợ: " + repaymentMethodLabel(application));
        line(document, "Ngày giải ngân dự kiến: " + schedule.getExpectedDisbursementDate());
        line(document, "Tổng tiền lãi dự kiến: " + moneyText(schedule.getTotalInterest()));
        line(document, "Tổng phí: " + moneyText(schedule.getTotalFees()));
        line(document, "Tổng tiền phạt dự kiến: " + moneyText(schedule.getTotalPenalties()));
        line(document, "Tổng số tiền phải trả dự kiến: " + moneyText(schedule.getTotalRepayment()));
        line(document, "Số tiền kỳ đầu: " + moneyText(schedule.getFirstInstallment()));
        line(document, "Số tiền kỳ cao nhất: " + moneyText(schedule.getMaximumInstallment()));
        blankLine(document);
        line(document, "3. LỊCH TRẢ NỢ DỰ KIẾN");
        line(document, "Lịch dưới đây được lập theo ngày giải ngân dự kiến. Lịch chính thức có thể được cập nhật theo ngày giải ngân thực tế.");
        appendPeriods(document, schedule.getPeriodsSnapshotJson());
        blankLine(document);
        line(document, LoanServicingContractTerms.plainText(
                application.getRequestedTermMonths(), ContractTextVersion.dailyInterest(application)));
        blankLine(document);
        line(document, "5. XÁC NHẬN CỦA NGƯỜI VAY");
        line(document, "Người vay xác nhận đã đọc số tiền vay, lãi suất, thời hạn, tổng nghĩa vụ thanh toán và toàn bộ lịch trả nợ nêu trên.");
        line(document, "Khi chọn \"Ký xác nhận\", FINORA gửi hoặc ghi nhận yêu cầu theo phương thức ký đang được hệ thống công bố; hợp đồng chỉ được xem là đã ký khi có bằng chứng hoàn tất tương ứng.");
        line(document, "Hạn xác nhận hợp đồng: " + expiresAt);
        blankLine(document);
        line(document, "6. THÔNG TIN PHIÊN BẢN VÀ ĐỐI CHIẾU");
        line(document, "Phiên bản điều khoản: " + termsVersion);
        line(document, "Phiên bản tài liệu: " + documentVersion);
        line(document, "Phiên bản chính sách tính lịch trả nợ: " + schedule.getCalculationPolicyVersion());
        line(document, "Mã đối chiếu lịch trả nợ: " + schedule.getResponseHash());
        return document.toString();
    }

    @Override
    public String renderFunded(
            ContractHeading heading,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            String documentVersion,
            Instant expiresAt,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash
    ) {
        StringBuilder document = new StringBuilder(render(
                heading, application, schedule, termsVersion, documentVersion, expiresAt));
        blankLine(document);
        line(document, "7. DANH SÁCH BÊN CHO VAY VÀ PHÂN BỔ ĐÃ KHÓA");
        line(document, "Phiên bản phân bổ: " + allocationVersion);
        line(document, "Mã đối chiếu phân bổ: " + allocationHash);
        allocations.forEach(allocation -> line(document, String.format(
                "Commitment %d — nhà đầu tư %s: %s (%s%%).",
                allocation.commitmentId(), allocation.investorId(), moneyText(allocation.amount()),
                percentage(allocation.sharePercent()))));
        line(document, "Tất cả bên cho vay ký cùng phiên bản tài liệu này trước khi mở lượt ký cho người vay.");
        return document.toString();
    }

    private void appendPeriods(StringBuilder document, String periodsJson) {
        try {
            JsonNode periods = objectMapper.readTree(periodsJson);
            if (!periods.isArray()) {
                throw new IllegalStateException("Schedule periods snapshot không phải JSON array");
            }
            for (JsonNode period : periods) {
                line(document, String.format(
                        "Kỳ %s — hạn %s: gốc %s; lãi %s; phí %s; phạt %s; tổng đến hạn %s; dư nợ còn lại %s.",
                        text(period, "period"),
                        text(period, "dueDate"),
                        moneyText(decimalValue(period, "principal")),
                        moneyText(decimalValue(period, "interest")),
                        moneyText(decimalValue(period, "fees")),
                        moneyText(decimalValue(period, "penalties")),
                        moneyText(decimalValue(period, "totalDue")),
                        moneyText(decimalValue(period, "outstandingBalance"))
                ));
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể đọc schedule snapshot để tạo Contract", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static BigDecimal decimalValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? BigDecimal.ZERO : value.decimalValue();
    }

    private static String moneyText(BigDecimal value) {
        String digits = value.setScale(0, RoundingMode.HALF_UP).toPlainString();
        StringBuilder grouped = new StringBuilder(digits.length() + digits.length() / 3);
        int firstGroup = digits.length() % 3;
        if (firstGroup == 0) {
            firstGroup = 3;
        }
        grouped.append(digits, 0, firstGroup);
        for (int index = firstGroup; index < digits.length(); index += 3) {
            grouped.append('.').append(digits, index, index + 3);
        }
        return grouped + " đồng";
    }

    private static String percentage(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String repaymentMethodLabel(LoanApplication application) {
        return switch (application.getRepaymentMethodSnapshot()) {
            case ANNUITY -> "Trả góp đều hằng kỳ";
            case EQUAL_PRINCIPAL -> "Trả gốc đều, lãi giảm dần";
        };
    }

    private static void line(StringBuilder document, String value) {
        document.append(value).append('\n');
    }

    private static void blankLine(StringBuilder document) {
        document.append('\n');
    }
}
