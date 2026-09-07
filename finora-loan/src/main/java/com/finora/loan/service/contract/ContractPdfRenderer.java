package com.finora.loan.service.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.support.HashingService;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Sinh PDF A4 phía server từ đúng snapshot Contract.
 *
 * <p>PDF được lưu ngay sau khi sinh; endpoint tải chỉ trả lại bytes đã lưu để hash không thay đổi
 * theo máy, font hoặc thời điểm render.</p>
 */
@Component
public class ContractPdfRenderer {

    public static final String DOCUMENT_VERSION = "CONTRACT_PDF_V1";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final HashingService hashingService;

    public ContractPdfRenderer(ObjectMapper objectMapper, HashingService hashingService) {
        this.objectMapper = objectMapper;
        this.hashingService = hashingService;
    }

    public ContractPdfArtifact renderSignable(
            String contractNumber,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            Instant expiresAt
    ) {
        byte[] content = render(html(
                contractNumber, application, schedule, termsVersion, expiresAt,
                "CHỜ NGƯỜI VAY XÁC NHẬN", null, null, null, null
        ));
        return artifact(ContractPdfArtifactType.SIGNABLE, content);
    }

    public ContractPdfArtifact renderSignedReceipt(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String signedDocumentHash
    ) {
        byte[] content = render(html(
                contract.getContractNumber(), application, schedule, contract.getTermsVersion(),
                contract.getExpiresAt(), "ĐÃ XÁC NHẬN CLICK-WRAP",
                contract.getSignedBy(), contract.getSignedAt(),
                contract.getSignatureMethod() == null ? null : contract.getSignatureMethod().name(),
                signedDocumentHash
        ));
        return artifact(ContractPdfArtifactType.SIGNED_RECEIPT, content);
    }

    private ContractPdfArtifact artifact(ContractPdfArtifactType type, byte[] content) {
        return new ContractPdfArtifact(type, DOCUMENT_VERSION, content, hashingService.sha256Bytes(content));
    }

    private byte[] render(String html) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> font("/fonts/DejaVuSans.ttf"), "DejaVu Sans");
            builder.useFont(() -> font("/fonts/DejaVuSans-Bold.ttf"), "DejaVu Sans", 700,
                    com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể sinh PDF hợp đồng", exception);
        }
    }

    private InputStream font(String path) {
        InputStream stream = ContractPdfRenderer.class.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Thiếu font PDF trong classpath: " + path);
        }
        return stream;
    }

    private String html(
            String contractNumber,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            Instant expiresAt,
            String receiptStatus,
            String signedBy,
            Instant signedAt,
            String signatureMethod,
            String signedDocumentHash
    ) {
        BigDecimal baseRate = application.getAnnualInterestRateSnapshot();
        BigDecimal finalRate = application.getFinalAnnualInterestRate() == null
                ? baseRate : application.getFinalAnnualInterestRate();
        return """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="vi">
                <head><meta charset="UTF-8"/><style>%s</style></head>
                <body>
                  <div class="watermark">BẢN THỬ NGHIỆM - CLICK-WRAP MVP</div>
                  <header class="national">
                    <div class="brand"><strong>FINORA</strong><span>Nền tảng kết nối cho vay ngang hàng</span></div>
                    <div class="country"><strong>CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM</strong><b>Độc lập - Tự do - Hạnh phúc</b><i>__________________</i></div>
                  </header>
                  <h1>HỢP ĐỒNG CHO VAY</h1>
                  <p class="center">Giao kết điện tử thông qua nền tảng FINORA</p>
                  <p class="center strong">Số: %s</p>
                  <div class="notice"><b>LƯU Ý VỀ BẢN THỬ NGHIỆM</b><br/>Artifact này phục vụ kiểm thử luồng PDF và click-wrap. Bên cho vay/chữ ký số SmartCA phải được tích hợp và thẩm định pháp lý trước khi dùng production.</div>
                  <h2>CĂN CỨ GIAO KẾT</h2>
                  <p>Bộ luật Dân sự 91/2015/QH13; Luật Giao dịch điện tử 20/2023/QH15; Luật Bảo vệ quyền lợi người tiêu dùng 19/2023/QH15; Nghị định 94/2025/NĐ-CP và nhu cầu hợp pháp của các bên.</p>
                  <h2>ĐIỀU KHOẢN TÀI CHÍNH CỐT LÕI</h2>
                  %s
                  <h2>THÔNG TIN GIAO KẾT</h2>
                  <table class="info"><tr><th>Mã hồ sơ vay</th><td>%s</td></tr><tr><th>Bên vay</th><td>Mã tham chiếu %s - thông tin định danh đầy đủ sẽ lấy từ User Service khi tích hợp production</td></tr><tr><th>Bên cho vay</th><td>Chưa xác lập trong LN-008; không giả mạo nhà đầu tư hoặc chữ ký SmartCA</td></tr><tr><th>Vai trò FINORA</th><td>Nền tảng kết nối và lưu bằng chứng giao dịch; không phải bên cho vay trong bản thiết kế P2P</td></tr></table>
                  <div class="page-break"></div>
                  <h2>ĐIỀU 1. SỐ TIỀN, MỤC ĐÍCH VÀ THỜI HẠN VAY</h2>
                  <ol><li>Số tiền vay: <b>%s</b>.</li><li>Mục đích vay: %s.</li><li>Thời hạn vay: <b>%d tháng</b>, tính từ ngày giải ngân thực tế. Ngày giải ngân dự kiến: %s.</li></ol>
                  <h2>ĐIỀU 2. LÃI SUẤT VÀ PHƯƠNG PHÁP TÍNH</h2>
                  <ol><li>Lãi suất cơ sở khi nộp hồ sơ: <b>%s%%/năm</b>. Lãi suất áp dụng cuối: <b>%s%%/năm</b>.</li><li>Phương thức trả nợ: %s; số liệu từng kỳ do Fineract tính theo snapshot đã chốt.</li><li>Tổng lãi dự kiến: <b>%s</b>. Lịch chính thức được xác định theo ngày giải ngân thực tế.</li></ol>
                  <h2>ĐIỀU 3. PHÍ VÀ NGHĨA VỤ THANH TOÁN</h2>
                  <ol><li>Tổng phí dự kiến: <b>%s</b>; tổng tiền phạt trong lịch dự kiến: <b>%s</b>.</li><li>Không thu khoản phí hoặc phạt không được công bố trong hợp đồng/phụ lục hợp lệ.</li><li>Cách xử lý quá hạn và thứ tự cấn trừ phải khớp cấu hình Fineract và điều khoản được pháp chế duyệt trước production.</li></ol>
                  <h2>ĐIỀU 4. GIẢI NGÂN VÀ THANH TOÁN</h2>
                  <ol><li>Giải ngân chỉ thực hiện sau khi đủ điều kiện tài trợ, xác minh và chữ ký hợp lệ của các bên.</li><li>Tiền giải ngân và trả nợ đi qua tài khoản hoặc ví điện tử của tổ chức được phép; FINORA không tự giữ tiền như tài khoản thanh toán độc lập.</li></ol>
                  <h2>ĐIỀU 5. QUYỀN VÀ CAM KẾT</h2>
                  <ul><li>Người vay được đọc, tải, lưu hợp đồng và từ chối nếu không đồng ý.</li><li>Các bên cung cấp thông tin trung thực, bảo mật dữ liệu và thực hiện đúng nghĩa vụ đã chấp thuận.</li><li>Không sửa âm thầm artifact đã công bố; thay đổi điều khoản trọng yếu phải tạo phiên bản mới.</li></ul>
                  <div class="page-break"></div>
                  <h1 class="appendix">PHỤ LỤC 01</h1><h2 class="center">LỊCH TRẢ NỢ DỰ KIẾN</h2>
                  <p class="center">Ngày giải ngân dự kiến: %s</p>
                  %s
                  <div class="notice blue"><b>CÁCH ĐỌC LỊCH</b><br/>“Phải trả” là tổng gốc, lãi, phí và phạt của kỳ. “Dư nợ” là phần gốc còn lại sau khi thanh toán kỳ tương ứng. Lịch chính thức có thể thay đổi theo ngày giải ngân thực tế và phải được cung cấp lại cho các bên.</div>
                  <div class="page-break"></div>
                  <h1>XÁC NHẬN VÀ BẰNG CHỨNG</h1>
                  <p>Các bên phải có thời gian hợp lý để đọc toàn bộ hợp đồng và phụ lục. Mọi chữ ký/xác nhận phải gắn với đúng phiên bản tài liệu và cho phép phát hiện thay đổi.</p>
                  <table class="signatures"><tr><th>BÊN CHO VAY</th><th>BÊN VAY</th></tr><tr><td><b>CHƯA TÍCH HỢP</b><br/>Investment Service/VNPT SmartCA chưa cung cấp bằng chứng ký.</td><td><b>%s</b><br/>%s</td></tr></table>
                  <h2>THÔNG TIN ĐỐI CHIẾU</h2>
                  <table class="info"><tr><th>Phiên bản điều khoản</th><td>%s</td></tr><tr><th>Phiên bản PDF</th><td>%s</td></tr><tr><th>Mã PDF đã xác nhận</th><td class="hash">%s</td></tr><tr><th>Hạn xác nhận</th><td>%s</td></tr><tr><th>Chính sách lịch trả</th><td>%s</td></tr><tr><th>Mã lịch trả</th><td class="hash">%s</td></tr></table>
                  <div class="notice blue"><b>XÁC NHẬN CỦA HỆ THỐNG FINORA</b><br/>FINORA ghi nhận phiên bản, trạng thái và thời điểm thao tác. Bản ghi click-wrap không được gọi là chữ ký số SmartCA.</div>
                </body></html>
                """.formatted(
                css(), escape(contractNumber), summary(application, schedule, finalRate),
                escape(application.getApplicationNumber()), escape(application.getBorrowerId()),
                money(application.getRequestedAmount()), escape(purpose(application)),
                application.getRequestedTermMonths(), date(schedule.getExpectedDisbursementDate()),
                percentage(baseRate), percentage(finalRate), repaymentMethod(application),
                money(schedule.getTotalInterest()), money(schedule.getTotalFees()),
                money(schedule.getTotalPenalties()), date(schedule.getExpectedDisbursementDate()),
                scheduleTable(schedule.getPeriodsSnapshotJson()), escape(receiptStatus),
                signatureEvidence(signedBy, signedAt, signatureMethod), escape(termsVersion),
                DOCUMENT_VERSION, escape(signedDocumentHash == null ? "Chưa xác nhận" : signedDocumentHash),
                dateTime(expiresAt), escape(schedule.getCalculationPolicyVersion()),
                escape(schedule.getResponseHash())
        );
    }

    private String summary(
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            BigDecimal finalRate
    ) {
        return """
                <table class="summary"><tr><td><span>SỐ TIỀN VAY</span><b>%s</b></td><td><span>THỜI HẠN</span><b>%d tháng</b></td></tr><tr><td><span>LÃI SUẤT ÁP DỤNG</span><b>%s%%/năm</b></td><td><span>TỔNG PHẢI TRẢ DỰ KIẾN</span><b>%s</b></td></tr></table>
                """.formatted(money(application.getRequestedAmount()), application.getRequestedTermMonths(),
                percentage(finalRate), money(schedule.getTotalRepayment()));
    }

    private String scheduleTable(String periodsJson) {
        try {
            JsonNode periods = objectMapper.readTree(periodsJson);
            if (!periods.isArray()) {
                throw new IllegalStateException("Schedule periods snapshot không phải JSON array");
            }
            StringBuilder rows = new StringBuilder();
            for (JsonNode period : periods) {
                rows.append("<tr><td>").append(escape(text(period, "period"))).append("</td><td>")
                        .append(escape(displayDate(text(period, "dueDate")))).append("</td><td><b>")
                        .append(money(decimal(period, "totalDue"))).append("</b><small>Gốc ")
                        .append(money(decimal(period, "principal"))).append(" - Lãi ")
                        .append(money(decimal(period, "interest"))).append(" - Phí/phạt ")
                        .append(money(decimal(period, "fees").add(decimal(period, "penalties"))))
                        .append("</small></td><td>").append(money(decimal(period, "outstandingBalance")))
                        .append("</td></tr>");
            }
            return "<table class=\"schedule\"><thead><tr><th>Kỳ</th><th>Ngày trả</th><th>Phải trả</th><th>Dư nợ gốc còn lại</th></tr></thead><tbody>"
                    + rows + "</tbody></table>";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể đọc schedule snapshot để sinh PDF", exception);
        }
    }

    private static String signatureEvidence(String actor, Instant at, String method) {
        if (actor == null || at == null || method == null) {
            return "Chưa có bằng chứng xác nhận của người vay";
        }
        return "Người xác nhận: " + escape(actor) + "<br/>Thời gian: " + dateTime(at)
                + "<br/>Phương thức: " + escape(method);
    }

    private static String purpose(LoanApplication application) {
        if (application.getPurposeDetail() == null) {
            return application.getPurposeCode().getLabel();
        }
        return application.getPurposeCode().getLabel() + ": " + application.getPurposeDetail();
    }

    private static String repaymentMethod(LoanApplication application) {
        return switch (application.getRepaymentMethodSnapshot()) {
            case ANNUITY -> "Trả góp đều hằng kỳ";
            case EQUAL_PRINCIPAL -> "Trả gốc đều, lãi giảm dần";
        };
    }

    private static String money(BigDecimal value) {
        String digits = value.setScale(0, RoundingMode.HALF_UP).toPlainString();
        StringBuilder grouped = new StringBuilder(digits.length() + digits.length() / 3);
        int first = digits.length() % 3;
        if (first == 0) {
            first = 3;
        }
        grouped.append(digits, 0, first);
        for (int index = first; index < digits.length(); index += 3) {
            grouped.append('.').append(digits, index, index + 3);
        }
        return grouped + " đồng";
    }

    private static String percentage(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String displayDate(String isoDate) {
        return java.time.LocalDate.parse(isoDate).format(DATE);
    }

    private static String date(java.time.LocalDate value) {
        return value.format(DATE);
    }

    private static String dateTime(Instant value) {
        return DATE_TIME.format(value.atZone(VIETNAM));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? BigDecimal.ZERO : value.decimalValue();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String css() {
        return """
                @page { size: A4; margin: 17mm 16mm 19mm; @bottom-left { content: "FINORA - CONTRACT_PDF_V1"; color: #64748b; font-size: 8px; } @bottom-right { content: "Trang " counter(page) " / " counter(pages); color: #64748b; font-size: 8px; } }
                * { box-sizing: border-box; } body { font-family: "DejaVu Sans"; color: #172033; font-size: 10.3pt; line-height: 1.52; margin: 0; }
                .watermark { position: fixed; top: 47%; left: 13%; transform: rotate(-32deg); color: #e7ebf1; font-size: 24pt; font-weight: 700; z-index: -1; }
                .national { display: table; width: 100%; border-bottom: 2px solid #1d5bd7; padding-bottom: 10px; margin-bottom: 24px; }
                .brand, .country { display: table-cell; vertical-align: top; } .brand { width: 34%; color: #153a70; } .brand strong { display: block; font-size: 18pt; } .brand span { display: block; font-size: 7.5pt; color: #607089; }
                .country { text-align: center; font-size: 9pt; } .country strong, .country b, .country i { display: block; } .country i { font-style: normal; }
                h1 { text-align: center; font-size: 19pt; margin: 14px 0 2px; } h1.appendix { font-size: 12pt; color: #153a70; }
                h2 { color: #153a70; font-size: 11.5pt; margin: 17px 0 7px; page-break-after: avoid; } p { margin: 5px 0; text-align: justify; }
                .center { text-align: center; } .strong { font-weight: 700; } ol, ul { padding-left: 22px; } li { margin: 5px 0; text-align: justify; }
                .notice { border: 1px solid #e8c96b; border-left: 4px solid #a15c00; background: #fff7dd; padding: 10px 12px; margin: 16px 0; }
                .notice.blue { border-color: #bfdbfe; border-left-color: #1d5bd7; background: #eff5ff; }
                table { border-collapse: collapse; width: 100%; } .summary td { width: 50%; border: 1px solid #d7dfea; background: #f6f8fb; padding: 10px; }
                .summary span { display: block; color: #607089; font-size: 7.5pt; margin-bottom: 4px; } .summary b { font-size: 11pt; }
                .info th, .info td { border-bottom: 1px solid #d7dfea; padding: 8px; vertical-align: top; } .info th { width: 31%; color: #607089; text-align: left; background: #f6f8fb; font-size: 8.5pt; }
                .schedule { font-size: 8.5pt; } .schedule th { background: #153a70; color: white; padding: 8px 5px; text-align: center; } .schedule td { border: 1px solid #d7dfea; padding: 7px 5px; vertical-align: top; text-align: right; page-break-inside: avoid; }
                .schedule td:first-child, .schedule td:nth-child(2) { text-align: center; } .schedule small { display: block; color: #607089; font-size: 7pt; margin-top: 3px; }
                .signatures { margin-top: 20px; } .signatures th { background: #eff5ff; color: #153a70; padding: 10px; border: 1px solid #d7dfea; }
                .signatures td { width: 50%; height: 92px; border: 1px solid #d7dfea; text-align: center; vertical-align: middle; padding: 10px; }
                .hash { word-wrap: break-word; font-size: 8pt; } .page-break { page-break-before: always; }
                """;
    }
}
