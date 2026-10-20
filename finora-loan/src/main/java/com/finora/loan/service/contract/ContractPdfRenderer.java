package com.finora.loan.service.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.ContractPartyStatus;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.SignatureProviderType;
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
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sinh PDF A4 phía server từ đúng snapshot Contract.
 *
 * <p>PDF được lưu ngay sau khi sinh; endpoint tải chỉ trả lại bytes đã lưu để hash không thay đổi
 * theo máy, font hoặc thời điểm render.</p>
 */
@Component
public class ContractPdfRenderer {

    /** Bản PDF cho khoản vay core V1–V3; core V4 dùng {@link ContractTextVersion#DAILY_INTEREST_PDF_VERSION}. */
    public static final String DOCUMENT_VERSION = "CONTRACT_PDF_V2";
    /** Hậu tố version cho bản ký PAdES (R5): cùng điều khoản, khác trang xác nhận ký số. */
    public static final String PADES_VERSION_SUFFIX = "_PADES";
    /** Trang biên nhận chữ ký số tách rời của hợp đồng PAdES (Q9, D45). */
    public static final String RECEIPT_DOCUMENT_VERSION = "PADES_RECEIPT_V1";
    private static final String NO_BORROWER_EVIDENCE = "Chưa có bằng chứng xác nhận của người vay";
    private static final String DETACHED_RECEIPT_NOTICE = "<b>XÁC NHẬN CỦA HỆ THỐNG FINORA</b><br/>FINORA ghi nhận"
            + " phiên bản, trạng thái, thời điểm và phương thức thao tác. Receipt này là bằng chứng hệ thống tách rời;"
            + " không tự chứng minh chữ ký PAdES đã được nhúng vào PDF.";
    private static final String PADES_NOTICE = "<b>CHỮ KÝ SỐ VÀ TRANG BIÊN NHẬN</b><br/>Mỗi bên ký số bằng chữ ký"
            + " ẩn theo chuẩn PAdES-B-B (ETSI.CAdES.detached), nhúng vào chính tệp này dưới dạng bản cập nhật gia tăng:"
            + " các bên cho vay ký trước theo thứ tự hoàn tất, người vay ký sau cùng; chữ ký sau không thay đổi phần đã"
            + " được ký trước. Xem và kiểm tra chữ ký trong bảng chữ ký của trình đọc PDF. Sau chữ ký cuối, FINORA phát"
            + " hành trang biên nhận chữ ký số riêng, dẫn chiếu mã SHA-256 của tệp đã ký. Chứng thư ở môi trường"
            + " demo/UAT không có giá trị pháp lý.";
    private static final String MOCK_LEGAL_NOTICE = "<div class=\"notice\"><b>CHỮ KÝ DEMO - KHÔNG CÓ GIÁ TRỊ PHÁP LÝ"
            + "</b><br/>Chữ ký được tạo bằng chứng thư tự ký \"FINORA DEMO - KHONG CO GIA TRI PHAP LY\", sinh tạm thời"
            + " mỗi lần khởi động dịch vụ. Chữ ký chỉ chứng minh kỹ thuật tính toàn vẹn của tệp; không phải chữ ký số"
            + " của các bên theo Luật Giao dịch điện tử.</div>";
    private static final String UAT_LEGAL_NOTICE = "<div class=\"notice\"><b>CHỨNG THƯ VNPT SMARTCA MÔI TRƯỜNG UAT"
            + "</b><br/>Chữ ký được tạo bằng chứng thư thử nghiệm của VNPT SmartCA UAT, có thể dùng chung một thuê bao"
            + " test cho nhiều vai trò; đây không phải chữ ký số có giá trị pháp lý production. Trước khi dùng thật cần"
            + " chứng thư production của đúng từng bên và thẩm định pháp lý.</div>";
    private static final String RECEIPT_CSS = """
            .receipt { margin: 10px 0 14px; page-break-inside: avoid; border-top: 2px solid #153a70; }
            .receipt .hash { word-break: break-all; }
            """;
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
                "CHỜ NGƯỜI VAY XÁC NHẬN", null, null, null, null, pdfVersion(application)
        ));
        return artifact(ContractPdfArtifactType.SIGNABLE, pdfVersion(application), content);
    }

    public ContractPdfArtifact renderFundedSignable(
            String contractNumber,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            Instant expiresAt,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash
    ) {
        return renderFundedSignable(contractNumber, application, schedule, termsVersion, expiresAt,
                allocations, allocationVersion, allocationHash, false);
    }

    /**
     * Bản PAdES (R5, Q9) giữ nguyên điều khoản của phiên bản gốc, chỉ đổi trang xác nhận: mô tả thứ tự ký số ẩn và chỉ
     * ra trang biên nhận nằm ngoài tệp. Đổi văn bản nên mang version riêng ({@code ..._PADES}).
     */
    public ContractPdfArtifact renderFundedSignable(
            String contractNumber,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            Instant expiresAt,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash,
            boolean pades
    ) {
        String version = pades ? padesPdfVersion(application) : pdfVersion(application);
        String base = html(
                contractNumber, application, schedule, termsVersion, expiresAt,
                pades ? "KÝ SỐ SAU CÙNG" : "CHỜ CÁC BÊN KÝ", null, null, null, null, version);
        String lenderRows = allocations.stream()
                .map(allocation -> "<tr><td>" + allocation.commitmentId() + "</td><td>"
                        + escape(allocation.investorId()) + "</td><td>"
                        + money(allocation.amount()) + "</td><td>"
                        + percentage(allocation.sharePercent()) + "%</td></tr>")
                .collect(java.util.stream.Collectors.joining());
        String appendix = """
                <div class="page-break"></div>
                <h1 class="appendix">PHỤ LỤC 02</h1>
                <h2 class="center">DANH SÁCH BÊN CHO VAY VÀ PHÂN BỔ ĐÃ KHÓA</h2>
                <table class="info"><tr><th>Commitment</th><th>Mã nhà đầu tư</th><th>Số tiền</th><th>Tỷ lệ</th></tr>%s</table>
                <table class="info"><tr><th>Phiên bản phân bổ</th><td>%d</td></tr><tr><th>Mã đối chiếu phân bổ</th><td class="hash">%s</td></tr></table>
                <div class="notice blue">Mỗi nhà đầu tư trong danh sách ký trên cùng PDF/hash. Người vay chỉ được ký sau khi toàn bộ bên cho vay hoàn tất.</div>
                """.formatted(lenderRows, allocationVersion, escape(allocationHash));
        String lenderCell = pades
                ? "<b>KÝ SỐ TRƯỚC</b><br/>" + allocations.size()
                        + " bên cho vay lần lượt ký số ẩn (PAdES-B-B) trên chính tệp này trước người vay."
                : "<b>CHỜ KÝ</b><br/>" + allocations.size() + " bên cho vay ký cùng PDF/hash trước người vay.";
        String funded = base
                .replace("Chưa xác lập trong LN-008; không giả mạo nhà đầu tư hoặc chữ ký SmartCA",
                        allocations.size() + " nhà đầu tư theo Phụ lục 02; danh sách đã khóa từ Investment Service")
                .replace("<b>CHƯA TÍCH HỢP</b><br/>Investment Service/VNPT SmartCA chưa cung cấp bằng chứng ký.",
                        lenderCell)
                .replace("</body></html>", appendix + "</body></html>");
        if (pades) {
            funded = replaceRequired(funded, NO_BORROWER_EVIDENCE,
                    "Người vay ký số ẩn (PAdES-B-B) sau cùng, khi đủ chữ ký bên cho vay.");
            // Tệp không thể chứa hash của chính nó; hash bản đã ký nằm ở trang biên nhận tách rời.
            funded = replaceRequired(funded, "<td class=\"hash\">Chưa xác nhận</td>",
                    "<td class=\"hash\">Không in trong tệp; mã SHA-256 của tệp đã ký nằm ở trang biên nhận"
                            + " chữ ký số</td>");
            funded = replaceRequired(funded, DETACHED_RECEIPT_NOTICE, PADES_NOTICE);
        }
        byte[] content = render(funded);
        return artifact(ContractPdfArtifactType.SIGNABLE, version, content);
    }

    /**
     * Trang biên nhận chữ ký số (Q9, D45): lập sau chữ ký cuối nên là tài liệu riêng, không nằm trong phần các bên đã
     * ký; dẫn chiếu SHA-256 của SIGNABLE và của tệp PAdES đã ký đủ, liệt kê từng chữ ký đã kiểm theo thứ tự.
     */
    public ContractPdfArtifact renderSignatureReceipt(ContractSignatureReceipt receipt) {
        // Mỗi chữ ký một khối hai cột: bảng tám cột trên A4 dọc làm ngắt giữa mã bên ký và thời điểm.
        String rows = receipt.rows().stream()
                .map(row -> "<table class=\"info receipt\">"
                        + "<tr><th>Chữ ký số thứ " + row.sequence() + "</th><td><b>ĐÃ KÝ</b> - toàn vẹn hợp lệ"
                        + " khi lập biên nhận</td></tr>"
                        + "<tr><th>Bên ký</th><td>"
                        + escape(row.signerType() == ContractPartyType.LENDER ? "Bên cho vay" : "Bên vay")
                        + " - mã " + escape(row.signerId()) + "</td></tr>"
                        + "<tr><th>Thời điểm ký</th><td>" + dateTime(row.signingTime()) + " (giờ Việt Nam)</td></tr>"
                        + "<tr><th>Phương thức</th><td>" + escape(row.provider().name() + " / " + row.method().name())
                        + " - " + escape(row.provider() == SignatureProviderType.MOCK
                        ? "chữ ký demo" : "VNPT SmartCA UAT") + "</td></tr>"
                        + "<tr><th>Chứng thư</th><td>" + escape(row.certificateCommonName())
                        + "<br/>Serial: " + escape(row.certificateSerial())
                        + "<br/>Tổ chức cấp: " + escape(row.issuerCommonName()) + "</td></tr>"
                        + "<tr><th>SHA-256 chứng thư</th><td class=\"hash\">" + escape(row.certificateSha256())
                        + "</td></tr>"
                        + "<tr><th>SHA-256 phiên bản đã ký</th><td class=\"hash\">" + escape(row.revisionHash())
                        + "<br/>" + row.revisionLength() + " byte đầu tệp</td></tr></table>")
                .collect(java.util.stream.Collectors.joining());
        String notices = receipt.rows().stream()
                .map(ContractSignatureReceipt.Row::provider)
                .distinct()
                .map(provider -> provider == SignatureProviderType.MOCK ? MOCK_LEGAL_NOTICE : UAT_LEGAL_NOTICE)
                .collect(java.util.stream.Collectors.joining());
        String html = """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="vi">
                <head><meta charset="UTF-8"/><style>%s%s</style></head>
                <body>
                  <div class="watermark">BẢN THỬ NGHIỆM - KHÔNG DÙNG PRODUCTION</div>
                  <header class="national">
                    <div class="brand"><strong>FINORA</strong><span>Nền tảng kết nối cho vay ngang hàng</span></div>
                    <div class="country"><strong>CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM</strong><b>Độc lập - Tự do - Hạnh phúc</b><i>__________________</i></div>
                  </header>
                  <h1>TRANG BIÊN NHẬN CHỮ KÝ SỐ</h1>
                  <p class="center strong">Hợp đồng cho vay số: %s</p>
                  <p class="center">Mã hồ sơ vay: %s</p>
                  %s
                  <h2>TỆP HỢP ĐỒNG ĐƯỢC KÝ</h2>
                  <table class="info"><tr><th>Bản các bên đã đọc (SIGNABLE)</th><td class="hash">%s</td></tr><tr><th>Phiên bản tài liệu</th><td>%s</td></tr><tr><th>Bản đã ký đủ (PAdES-B-B)</th><td class="hash">%s</td></tr><tr><th>Kích thước bản đã ký</th><td>%d byte</td></tr><tr><th>Số chữ ký nhúng</th><td>%d - bên cho vay ký trước, người vay ký sau cùng</td></tr><tr><th>Định dạng</th><td>PAdES-B-B, ETSI.CAdES.detached, SHA-256, chữ ký ẩn; mỗi chữ ký là một bản cập nhật gia tăng</td></tr><tr><th>Thời điểm lập biên nhận</th><td>%s</td></tr></table>
                  <h2>CÁC CHỮ KÝ THEO THỨ TỰ</h2>
                  %s
                  <div class="notice blue"><b>VÌ SAO TRANG NÀY NẰM NGOÀI TỆP ĐÃ KÝ</b><br/>Mỗi chữ ký PAdES bao phủ toàn bộ tệp tại thời điểm ký. Trang biên nhận được lập sau chữ ký cuối cùng nên không thể nằm trong phần các bên đã ký; chèn thêm trang vào tệp sau khi ký sẽ làm trình đọc PDF coi các chữ ký trước là đã bị sửa đổi. Vì vậy FINORA phát hành biên nhận thành tài liệu riêng, dẫn chiếu mã SHA-256 của tệp đã ký ở trên.</div>
                  <div class="notice blue"><b>CÁCH KIỂM TRA</b><br/>Mở tệp đã ký bằng trình đọc PDF có bảng chữ ký: mỗi chữ ký báo trạng thái toàn vẹn của đúng phiên bản nó đã ký. Chứng thư demo/UAT không thuộc danh sách tin cậy nên trình đọc báo chưa xác minh được danh tính người ký; điều này đúng với bản chất thử nghiệm.</div>
                </body></html>
                """.formatted(
                css(RECEIPT_DOCUMENT_VERSION), RECEIPT_CSS, escape(receipt.contractNumber()),
                escape(receipt.applicationNumber()), notices, escape(receipt.signableHash()),
                escape(receipt.signableVersion()), escape(receipt.signedPdfHash()), receipt.signedPdfLength(),
                receipt.rows().size(), dateTime(receipt.issuedAt()), rows);
        return artifact(ContractPdfArtifactType.SIGNED_RECEIPT, RECEIPT_DOCUMENT_VERSION, render(html));
    }

    public ContractPdfArtifact renderSignedReceipt(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String signedDocumentHash
    ) {
        byte[] content = render(html(
                contract.getContractNumber(), application, schedule, contract.getTermsVersion(),
                contract.getExpiresAt(), receiptStatus(contract),
                contract.getSignedBy(), contract.getSignedAt(),
                contract.getSignatureMethod() == null ? null : contract.getSignatureMethod().name(),
                signedDocumentHash, pdfVersion(application)
        ));
        return artifact(ContractPdfArtifactType.SIGNED_RECEIPT, pdfVersion(application), content);
    }

    public ContractPdfArtifact renderSignedReceipt(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String signedDocumentHash,
            List<ContractParty> parties
    ) {
        List<ContractParty> lenders = parties.stream()
                .filter(party -> party.getPartyType() == ContractPartyType.LENDER)
                .toList();
        String base = html(
                contract.getContractNumber(), application, schedule, contract.getTermsVersion(),
                contract.getExpiresAt(), receiptStatus(contract), contract.getSignedBy(),
                contract.getSignedAt(),
                contract.getSignatureMethod() == null ? null : contract.getSignatureMethod().name(),
                signedDocumentHash, pdfVersion(application))
                .replace("Chưa xác lập trong LN-008; không giả mạo nhà đầu tư hoặc chữ ký SmartCA",
                        lenders.size() + " nhà đầu tư theo Phụ lục bằng chứng chữ ký")
                .replace("<b>CHƯA TÍCH HỢP</b><br/>Investment Service/VNPT SmartCA chưa cung cấp bằng chứng ký.",
                        lenderSignatureSummary(lenders));
        String rows = lenders.stream()
                .map(party -> "<tr><td>" + escape(party.getPartyId()) + "</td><td>"
                        + money(party.getAllocationAmount()) + "</td><td>"
                        + escape(partyStatusLabel(party.getStatus())) + "</td><td>"
                        + escape(signatureMethodLabel(party)) + "</td><td>"
                        + dateTime(party.getSignedAt()) + "</td><td class=\"hash\">"
                        + escape(party.getSignatureEvidenceHash() == null
                        ? "Chưa ký" : party.getSignatureEvidenceHash()) + "</td></tr>")
                .collect(java.util.stream.Collectors.joining());
        String evidence = """
                <div class="page-break"></div>
                <h1>BẰNG CHỨNG CHỮ KÝ CÁC BÊN CHO VAY</h1>
                <table class="info"><tr><th>Mã nhà đầu tư</th><th>Phần vốn</th><th>Trạng thái</th><th>Phương thức</th><th>Thời điểm ký</th><th>Evidence hash</th></tr>%s</table>
                """.formatted(rows);
        byte[] content = render(base.replace("</body></html>", evidence + "</body></html>"));
        return artifact(ContractPdfArtifactType.SIGNED_RECEIPT, pdfVersion(application), content);
    }

    private static String lenderSignatureSummary(List<ContractParty> lenders) {
        long signed = lenders.stream()
                .filter(party -> party.getStatus() == ContractPartyStatus.SIGNED)
                .count();
        if (!lenders.isEmpty() && signed == lenders.size()) {
            return "<b>ĐÃ KÝ</b><br/>" + signed
                    + " bên cho vay đã hoàn tất ký; xem bằng chứng chi tiết tại phụ lục cuối tài liệu.";
        }
        return "<b>CHƯA HOÀN TẤT</b><br/>Đã ký " + signed + "/" + lenders.size()
                + " bên cho vay; xem bằng chứng chi tiết tại phụ lục cuối tài liệu.";
    }

    private static String partyStatusLabel(ContractPartyStatus status) {
        return switch (status) {
            case SIGNED -> "Đã ký";
            case SIGNING -> "Đang chờ SmartCA";
            case PENDING_SIGNATURE -> "Chờ ký";
            case DECLINED -> "Đã từ chối";
            case EXPIRED -> "Đã hết hạn";
        };
    }

    private static String signatureMethodLabel(ContractParty party) {
        if (party.getSignatureProvider() == null || party.getSignatureMethod() == null) {
            return "Chưa có";
        }
        return party.getSignatureProvider().name() + " / " + party.getSignatureMethod().name();
    }

    private ContractPdfArtifact artifact(ContractPdfArtifactType type, String version, byte[] content) {
        return new ContractPdfArtifact(type, version, content, hashingService.sha256Bytes(content));
    }

    /** PDF của hồ sơ core V4 mang điều 4 tính lãi theo ngày nên là một phiên bản tài liệu riêng. */
    private static String pdfVersion(LoanApplication application) {
        return ContractTextVersion.dailyInterest(application)
                ? ContractTextVersion.DAILY_INTEREST_PDF_VERSION : DOCUMENT_VERSION;
    }

    /** Cùng điều khoản với bản gốc, khác trang xác nhận ký số; ví dụ {@code CONTRACT_PDF_V3_PADES}. */
    public static String padesPdfVersion(LoanApplication application) {
        return pdfVersion(application) + PADES_VERSION_SUFFIX;
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
            String signedDocumentHash,
            String pdfVersion
    ) {
        BigDecimal baseRate = application.getAnnualInterestRateSnapshot();
        BigDecimal finalRate = application.getFinalAnnualInterestRate() == null
                ? baseRate : application.getFinalAnnualInterestRate();
        return """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="vi">
                <head><meta charset="UTF-8"/><style>%s</style></head>
                <body>
                  <div class="watermark">BẢN THỬ NGHIỆM - KHÔNG DÙNG PRODUCTION</div>
                  <header class="national">
                    <div class="brand"><strong>FINORA</strong><span>Nền tảng kết nối cho vay ngang hàng</span></div>
                    <div class="country"><strong>CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM</strong><b>Độc lập - Tự do - Hạnh phúc</b><i>__________________</i></div>
                  </header>
                  <h1>HỢP ĐỒNG CHO VAY</h1>
                  <p class="center">Giao kết điện tử thông qua nền tảng FINORA</p>
                  <p class="center strong">Số: %s</p>
                  <div class="notice"><b>LƯU Ý VỀ BẢN THỬ NGHIỆM</b><br/>Artifact này phục vụ kiểm thử luồng PDF và xác nhận điện tử. Bên cho vay và hình thức ký phải được tích hợp, kiểm chứng và thẩm định pháp lý trước khi dùng production.</div>
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
                  %s
                  <h2>ĐIỀU 5. GIẢI NGÂN VÀ THANH TOÁN</h2>
                  <ol><li>Giải ngân chỉ thực hiện sau khi đủ điều kiện tài trợ, xác minh và chữ ký hợp lệ của các bên.</li><li>Tiền giải ngân và trả nợ đi qua tài khoản hoặc ví điện tử của tổ chức được phép; FINORA không tự giữ tiền như tài khoản thanh toán độc lập.</li></ol>
                  <h2>ĐIỀU 6. QUYỀN VÀ CAM KẾT</h2>
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
                  <div class="notice blue"><b>XÁC NHẬN CỦA HỆ THỐNG FINORA</b><br/>FINORA ghi nhận phiên bản, trạng thái, thời điểm và phương thức thao tác. Receipt này là bằng chứng hệ thống tách rời; không tự chứng minh chữ ký PAdES đã được nhúng vào PDF.</div>
                </body></html>
                """.formatted(
                css(pdfVersion), escape(contractNumber), summary(application, schedule, finalRate),
                escape(application.getApplicationNumber()), escape(application.getBorrowerId()),
                money(application.getRequestedAmount()), escape(purpose(application)),
                application.getRequestedTermMonths(), date(schedule.getExpectedDisbursementDate()),
                percentage(baseRate), percentage(finalRate), repaymentMethod(application),
                money(schedule.getTotalInterest()), money(schedule.getTotalFees()),
                money(schedule.getTotalPenalties()),
                LoanServicingContractTerms.html(
                        application.getRequestedTermMonths(), ContractTextVersion.dailyInterest(application)),
                date(schedule.getExpectedDisbursementDate()),
                scheduleTable(schedule.getPeriodsSnapshotJson()), escape(receiptStatus),
                signatureEvidence(signedBy, signedAt, signatureMethod), escape(termsVersion),
                pdfVersion, escape(signedDocumentHash == null ? "Chưa xác nhận" : signedDocumentHash),
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
            return NO_BORROWER_EVIDENCE;
        }
        return "Người xác nhận: " + escape(actor) + "<br/>Thời gian: " + dateTime(at)
                + "<br/>Phương thức: " + escape(method);
    }

    private static String receiptStatus(LoanContract contract) {
        if (contract.getSignatureMethod() == null) {
            return "ĐÃ GHI NHẬN XÁC NHẬN";
        }
        return switch (contract.getSignatureMethod()) {
            case CLICK_WRAP_MVP -> "ĐÃ XÁC NHẬN CLICK-WRAP";
            case VNPT_SMART_CA -> "ĐÃ NHẬN BẰNG CHỨNG VNPT SMARTCA";
        };
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

    /** Bản PAdES thay đúng đoạn văn của mẫu gốc; mẫu gốc đổi mà quên cập nhật thì dừng thay vì in sai trang ký. */
    private static String replaceRequired(String source, String target, String replacement) {
        if (!source.contains(target)) {
            throw new IllegalStateException("Mẫu PDF hợp đồng không còn đoạn cần thay cho bản PAdES");
        }
        return source.replace(target, replacement);
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

    private static String css(String pdfVersion) {
        return """
                @page { size: A4; margin: 17mm 16mm 19mm; @bottom-left { content: "FINORA - PDF_VERSION"; color: #64748b; font-size: 8px; } @bottom-right { content: "Trang " counter(page) " / " counter(pages); color: #64748b; font-size: 8px; } }
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
                """.replace("PDF_VERSION", pdfVersion);
    }
}
