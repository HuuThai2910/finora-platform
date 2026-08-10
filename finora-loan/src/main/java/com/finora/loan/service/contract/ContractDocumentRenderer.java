package com.finora.loan.service.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Render văn bản dễ đọc nhưng vẫn xác định để cùng điều khoản luôn sinh cùng bytes UTF-8 và SHA-256. */
@Component
public class ContractDocumentRenderer {

    private final ObjectMapper objectMapper;

    public ContractDocumentRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String render(
            String contractNumber,
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
        line(document, "Mã hợp đồng: " + contractNumber);
        line(document, "Mã hồ sơ vay: " + application.getApplicationNumber());
        line(document, "Mã tham chiếu người vay: " + application.getBorrowerId());
        blankLine(document);
        line(document, "2. ĐIỀU KHOẢN KHOẢN VAY");
        line(document, "Số tiền vay: " + moneyText(application.getRequestedAmount()));
        line(document, "Thời hạn vay: " + application.getRequestedTermMonths() + " tháng");
        line(document, "Lãi suất cố định: " + percentage(application.getAnnualInterestRateSnapshot()) + "%/năm");
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
        line(document, "4. XÁC NHẬN CỦA NGƯỜI VAY");
        line(document, "Người vay xác nhận đã đọc số tiền vay, lãi suất, thời hạn, tổng nghĩa vụ thanh toán và toàn bộ lịch trả nợ nêu trên.");
        line(document, "Khi chọn \"Ký xác nhận\", FINORA ghi nhận sự đồng ý bằng hình thức click-wrap trong hệ thống; đây chưa phải chữ ký số SmartCA.");
        line(document, "Hạn xác nhận hợp đồng: " + expiresAt);
        blankLine(document);
        line(document, "5. THÔNG TIN PHIÊN BẢN VÀ ĐỐI CHIẾU");
        line(document, "Phiên bản điều khoản: " + termsVersion);
        line(document, "Phiên bản tài liệu: " + documentVersion);
        line(document, "Phiên bản chính sách tính lịch trả nợ: " + schedule.getCalculationPolicyVersion());
        line(document, "Mã đối chiếu lịch trả nợ: " + schedule.getResponseHash());
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
