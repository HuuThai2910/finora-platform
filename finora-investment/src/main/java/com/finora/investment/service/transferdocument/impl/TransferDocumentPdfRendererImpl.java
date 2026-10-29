package com.finora.investment.service.transferdocument.impl;

import com.finora.investment.service.transferdocument.TransferDocumentContent;
import com.finora.investment.service.transferdocument.TransferDocumentPdfRenderer;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Dựng PDF A4 "Giấy xác nhận chuyển nhượng Note" từ {@link TransferDocumentContent}. Cùng font DejaVu và bố cục với
 * hợp đồng vay của Loan để hai loại văn bản trông cùng một bộ.
 */
@Component
public class TransferDocumentPdfRendererImpl implements TransferDocumentPdfRenderer {

    static final String DOCUMENT_VERSION = "NOTE_TRANSFER_PDF_V1";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss 'ngày' dd/MM/yyyy");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd 'tháng' MM 'năm' yyyy");
    private static final String DEMO_NOTICE = "<div class=\"notice\"><b>CON DẤU DEMO - KHÔNG CÓ GIÁ TRỊ PHÁP LÝ</b><br/>"
            + "Văn bản được đóng dấu bằng chứng thư tự ký \"FINORA DEMO - KHONG CO GIA TRI PHAP LY\", sinh tạm thời mỗi"
            + " lần khởi động dịch vụ. Con dấu chỉ chứng minh kỹ thuật tính toàn vẹn của tệp.</div>";

    @Override
    public byte[] render(TransferDocumentContent content) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> font("/fonts/DejaVuSans.ttf"), "DejaVu Sans");
            builder.useFont(() -> font("/fonts/DejaVuSans-Bold.ttf"), "DejaVu Sans", 700,
                    BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html(content), null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể sinh PDF giấy xác nhận chuyển nhượng", exception);
        }
    }

    private static InputStream font(String path) {
        InputStream stream = TransferDocumentPdfRenderer.class.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Thiếu font PDF trong classpath: " + path);
        }
        return stream;
    }

    private static String html(TransferDocumentContent c) {
        StringBuilder notes = new StringBuilder();
        int index = 1;
        for (TransferDocumentContent.NoteLine note : c.notes()) {
            notes.append("<tr><td>").append(index++).append("</td><td>").append(escape(note.noteNumber()))
                    .append("</td><td>").append(money(note.outstandingAtTransfer()))
                    .append("</td><td>").append(money(note.price()))
                    .append("</td><td>").append(note.defaulted() ? "Nợ xấu" : "Đang thu nợ").append("</td></tr>");
        }
        String defaultedClause = c.defaulted()
                ? "<li>Một hoặc nhiều Note trong giao dịch thuộc khoản vay đang nợ xấu tại thời điểm khớp. Bên nhận"
                        + " chuyển nhượng đã xác nhận cảnh báo rủi ro này khi đặt lệnh mua.</li>"
                : "";
        return """
                <!DOCTYPE html>
                <html lang="vi">
                <head><meta charset="UTF-8"/><title>%1$s</title><style>%2$s</style></head>
                <body>
                %3$s
                <div class="national">
                  <div class="brand"><strong>FINORA</strong><span>Nền tảng cho vay ngang hàng</span></div>
                  <div class="country"><strong>CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM</strong><b>Độc lập - Tự do - Hạnh phúc</b></div>
                </div>
                <h1>GIẤY XÁC NHẬN CHUYỂN NHƯỢNG NOTE</h1>
                <p class="center">Số: <b>%1$s</b></p>
                <p>FINORA xác nhận giao dịch chuyển nhượng Note dưới đây đã được khớp trên sổ lệnh của nền tảng và đã
                thanh toán đầy đủ cho bên chuyển nhượng.</p>

                <h2>1. Các bên</h2>
                <table class="parties">
                  <tr><th></th><th>Bên chuyển nhượng</th><th>Bên nhận chuyển nhượng</th></tr>
                  <tr><td class="label">Họ và tên</td><td>%4$s</td><td>%5$s</td></tr>
                  <tr><td class="label">Số CCCD</td><td>%6$s</td><td>%7$s</td></tr>
                  <tr><td class="label">Mã nhà đầu tư</td><td class="hash">%8$s</td><td class="hash">%9$s</td></tr>
                </table>

                <h2>2. Khoản vay gốc</h2>
                <table class="info">
                  <tr><th>Số hợp đồng vay</th><td>%10$s</td></tr>
                  <tr><th>Mã hồ sơ vay</th><td>%11$s</td></tr>
                  <tr><th>Lãi suất danh nghĩa</th><td>%12$s</td></tr>
                  <tr><th>Kỳ hạn</th><td>%13$s</td></tr>
                </table>

                <h2>3. Giao dịch</h2>
                <table class="info">
                  <tr><th>Mã giao dịch</th><td>%14$s</td></tr>
                  <tr><th>Thời điểm khớp lệnh</th><td>%15$s</td></tr>
                  <tr><th>Thời điểm thanh toán</th><td>%16$s</td></tr>
                  <tr><th>Giá khớp</th><td>%17$s dư nợ gốc</td></tr>
                  <tr><th>Số Note</th><td>%18$d</td></tr>
                  <tr><th>Tổng dư nợ gốc chuyển nhượng</th><td>%19$s</td></tr>
                  <tr><th>Giá chuyển nhượng</th><td><b>%20$s</b></td></tr>
                  <tr><th>Phí nền tảng</th><td>%21$s</td></tr>
                  <tr><th>Bên chuyển nhượng thực nhận</th><td>%22$s</td></tr>
                </table>

                <h2>4. Danh sách Note</h2>
                <table class="notes">
                  <tr><th>STT</th><th>Mã Note</th><th>Dư nợ gốc khi chuyển</th><th>Giá chuyển nhượng</th><th>Tình trạng</th></tr>
                  %23$s
                </table>

                <h2>5. Nội dung xác nhận</h2>
                <ol>
                  <li>Kể từ thời điểm khớp lệnh, Bên nhận chuyển nhượng là chủ sở hữu các Note nêu tại mục 4 và được
                  nhận toàn bộ tiền gốc, lãi của các Note đó phát sinh từ thời điểm này.</li>
                  <li>Bên chuyển nhượng không còn quyền đối với các Note đã chuyển nhượng và đã nhận đủ khoản tiền nêu
                  tại mục 3.</li>
                  <li>Việc chuyển nhượng không làm thay đổi nghĩa vụ trả nợ của người vay theo hợp đồng vay gốc.</li>
                  <li>Hai bên đã đồng ý điều khoản giao dịch sổ lệnh khi đặt lệnh; FINORA khớp lệnh và chuyển tiền theo
                  đúng điều khoản đó.</li>
                  %24$s
                </ol>

                <h2>6. Xác thực văn bản</h2>
                <p>Văn bản được FINORA đóng dấu số bằng chữ ký PAdES ẩn nhúng trong tệp; kiểm tra trong bảng chữ ký
                của trình đọc PDF. Mã SHA-256 của tệp này được ghi lên sổ cái blockchain của FINORA với mã tham chiếu
                <b>NoteTransferDocument/%14$s</b>. Mọi chỉnh sửa tệp sau khi phát hành đều làm chữ ký và mã băm
                không còn khớp.</p>

                <table class="sign"><tr><td>
                  <p>Ngày %25$s</p>
                  <p class="strong">ĐẠI DIỆN NỀN TẢNG FINORA</p>
                  <p class="muted">(Đã đóng dấu số)</p>
                </td></tr></table>
                </body>
                </html>
                """.formatted(
                escape(c.documentNumber()),
                css(c.documentNumber()),
                c.demoSeal() ? DEMO_NOTICE : "",
                name(c.seller()), name(c.buyer()),
                idNumber(c.seller()), idNumber(c.buyer()),
                escape(c.seller().investorId()), escape(c.buyer().investorId()),
                orDash(c.contractNumber()),
                c.loanApplicationId(),
                c.annualInterestRate() == null ? "—" : percent(c.annualInterestRate()) + "/năm",
                c.termMonths() == null ? "—" : c.termMonths() + " tháng",
                escape(c.tradeReference()),
                dateTime(c.executedAt()),
                dateTime(c.settledAt()),
                percent(BigDecimal.valueOf(c.pricePermille(), 1)),
                c.quantity(),
                money(c.outstandingTotal()),
                money(c.amount()),
                money(c.platformFee()),
                money(c.sellerProceeds()),
                notes,
                defaultedClause,
                c.issuedAt().atZone(VIETNAM).format(DATE));
    }

    private static String css(String documentNumber) {
        return """
                @page { size: A4; margin: 17mm 16mm 19mm; @bottom-left { content: "FINORA - DOC_NUMBER - VERSION"; color: #64748b; font-size: 8px; } @bottom-right { content: "Trang " counter(page) " / " counter(pages); color: #64748b; font-size: 8px; } }
                * { box-sizing: border-box; } body { font-family: "DejaVu Sans"; color: #172033; font-size: 10.3pt; line-height: 1.52; margin: 0; }
                .national { display: table; width: 100%; border-bottom: 2px solid #1d5bd7; padding-bottom: 10px; margin-bottom: 20px; }
                .brand, .country { display: table-cell; vertical-align: top; } .brand { width: 34%; color: #153a70; } .brand strong { display: block; font-size: 18pt; } .brand span { display: block; font-size: 7.5pt; color: #607089; }
                .country { text-align: center; font-size: 9pt; } .country strong, .country b { display: block; }
                h1 { text-align: center; font-size: 17pt; margin: 12px 0 2px; }
                h2 { color: #153a70; font-size: 11.5pt; margin: 16px 0 7px; page-break-after: avoid; } p { margin: 5px 0; text-align: justify; }
                .center { text-align: center; } .strong { font-weight: 700; } .muted { color: #607089; font-size: 8.5pt; }
                ol { padding-left: 22px; } li { margin: 5px 0; text-align: justify; }
                .notice { border: 1px solid #e8c96b; border-left: 4px solid #a15c00; background: #fff7dd; padding: 10px 12px; margin: 0 0 14px; }
                table { border-collapse: collapse; width: 100%; }
                .info th, .info td { border-bottom: 1px solid #d7dfea; padding: 7px 8px; vertical-align: top; } .info th { width: 38%; color: #607089; text-align: left; background: #f6f8fb; font-size: 8.5pt; }
                .parties th { background: #eff5ff; color: #153a70; padding: 8px; border: 1px solid #d7dfea; } .parties td { border: 1px solid #d7dfea; padding: 8px; vertical-align: top; width: 40%; }
                .parties td.label { width: 20%; color: #607089; background: #f6f8fb; font-size: 8.5pt; }
                .notes { font-size: 8.8pt; } .notes th { background: #153a70; color: white; padding: 7px 5px; text-align: center; } .notes td { border: 1px solid #d7dfea; padding: 6px 5px; text-align: right; page-break-inside: avoid; }
                .notes td:first-child, .notes td:nth-child(2), .notes td:last-child { text-align: center; }
                .sign { margin-top: 18px; page-break-inside: avoid; } .sign td { width: 50%; margin-left: 50%; text-align: center; padding-left: 50%; }
                .hash { word-wrap: break-word; font-size: 8pt; }
                """.replace("DOC_NUMBER", documentNumber).replace("VERSION", DOCUMENT_VERSION);
    }

    private static String name(TransferDocumentContent.Party party) {
        return party.fullName() == null ? "<i>Chưa cập nhật hồ sơ</i>" : "<b>" + escape(party.fullName()) + "</b>";
    }

    private static String idNumber(TransferDocumentContent.Party party) {
        return party.maskedIdNumber() == null ? "<i>Chưa cập nhật</i>" : escape(party.maskedIdNumber());
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : escape(value);
    }

    private static String dateTime(Instant value) {
        return value == null ? "—" : value.atZone(VIETNAM).format(DATE_TIME);
    }

    /** Tiền VND: dấu chấm phân cách nghìn, giữ phần lẻ nếu có. */
    static String money(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));
        return format.format(value) + " ₫";
    }

    static String percent(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));
        return format.format(value) + "%";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
