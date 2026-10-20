package com.finora.notification.service.template;

import static com.finora.notification.domain.NotificationCategory.CONTRACT;
import static com.finora.notification.domain.NotificationCategory.DELINQUENCY;
import static com.finora.notification.domain.NotificationCategory.DISBURSEMENT;
import static com.finora.notification.domain.NotificationCategory.FUNDING;
import static com.finora.notification.domain.NotificationCategory.GENERAL;
import static com.finora.notification.domain.NotificationCategory.REPAYMENT;
import static com.finora.notification.domain.NotificationCategory.RESTRUCTURING;
import static com.finora.notification.domain.NotificationCategory.WALLET;

import com.finora.notification.domain.NotificationCategory;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Danh mục mẫu thông báo (D63). Tên hằng là mã {@code type} lưu ở hộp thư; tin của nhà đầu tư giữ đúng
 * {@code changeType} của Investment để app/E2E cũ đọc như trước.
 * <p>
 * Hai lớp nội dung:
 * <ul>
 *   <li>Trong app: đủ chi tiết (số tiền, mã hồ sơ/hợp đồng/khoản vay, ngày) vì chỉ người đã đăng nhập mới đọc được.
 *       Số tiền viết {@code <số thập phân> VND} (ví dụ {@code 1726000.00 VND}) vì app định dạng lại đúng mẫu này.</li>
 *   <li>Push: hiện trên màn hình khóa nên KHÔNG có số tiền, tên, mã khoản vay hay chữ số nào. Tin quá hạn/nợ xấu dùng câu
 *       hoàn toàn chung chung vì tình trạng nợ là thông tin nhạy cảm với người đứng cạnh.</li>
 * </ul>
 * Đổi câu chữ của một mẫu thì tăng {@link #version()}; thông báo đã lưu giữ nguyên bản cũ.
 */
public enum NotificationTemplate {

    // ---- Người vay: gọi vốn ----
    LOAN_FUNDING_OPENED(FUNDING, "Hồ sơ vay đã lên sàn gọi vốn",
            "Hồ sơ {application} đang được gọi vốn {amount} VND trên sàn FINORA. Bạn sẽ nhận thông báo khi đủ vốn.",
            "Cập nhật hồ sơ vay", "Hồ sơ vay của bạn đã lên sàn gọi vốn."),
    LOAN_FULLY_FUNDED(FUNDING, "Hồ sơ vay đã đủ vốn",
            "Hồ sơ {application} đã được gọi đủ {amount} VND. Hợp đồng đang chờ các nhà đầu tư ký; bạn ký sau cùng.",
            "Cập nhật hồ sơ vay", "Hồ sơ vay của bạn đã được gọi đủ vốn."),
    LOAN_FUNDING_EXPIRED(FUNDING, "Đợt gọi vốn đã kết thúc",
            "Hồ sơ {application} chưa gọi đủ vốn trước hạn (đã có {committed} VND trên tổng {amount} VND) nên không được lập hợp đồng.",
            "Cập nhật hồ sơ vay", "Đợt gọi vốn cho hồ sơ vay của bạn đã kết thúc."),
    LOAN_FUNDING_CANCELLED(FUNDING, "Khoản vay không được giải ngân",
            "{reason} Khoản vay {loan} sẽ không được giải ngân.",
            "Cập nhật hồ sơ vay", "Có cập nhật mới về hồ sơ vay của bạn."),

    // ---- Nhà đầu tư: gọi vốn ----
    INVESTMENT_FUNDING_EXPIRED(FUNDING, "Khoản đầu tư đã được hủy",
            "Hồ sơ {application} không gọi đủ vốn trước hạn. Tiền đang giữ cho khoản này sẽ được trả về ví của bạn.",
            "Cập nhật khoản đầu tư", "Một khoản đầu tư của bạn đã được hủy, tiền giữ sẽ về ví."),
    INVESTMENT_FUNDING_CANCELLED(FUNDING, "Khoản đầu tư đã được hủy",
            "{reason} Tiền đang giữ cho khoản vay {loan} sẽ được trả về ví của bạn.",
            "Cập nhật khoản đầu tư", "Một khoản đầu tư của bạn đã được hủy, tiền giữ sẽ về ví."),

    // ---- Hợp đồng ----
    INVESTOR_SIGNATURE_REQUESTED(CONTRACT, "Mời bạn ký hợp đồng cho vay",
            "Khoản vay {application} đã đủ vốn. Vui lòng ký hợp đồng {contract} trước {deadline}.",
            "Hợp đồng chờ ký", "Có hợp đồng cho vay đang chờ bạn ký."),
    BORROWER_SIGNATURE_REQUESTED(CONTRACT, "Đến lượt bạn ký hợp đồng",
            "Các nhà đầu tư ({lenderCount}) đã ký hợp đồng {contract}. Vui lòng ký trước {deadline} để khoản vay được giải ngân.",
            "Hợp đồng chờ ký", "Hợp đồng vay của bạn đang chờ bạn ký."),
    CONTRACT_ACTIVATED(CONTRACT, "Hợp đồng đã có hiệu lực",
            "Hợp đồng {contract} đã đủ chữ ký của các bên. FINORA đang giải ngân khoản vay.",
            "Cập nhật hợp đồng", "Hợp đồng vay của bạn đã đủ chữ ký."),
    INVESTOR_CONTRACT_ACTIVATED(CONTRACT, "Hợp đồng cho vay đã có hiệu lực",
            "Hợp đồng {contract} đã đủ chữ ký của các bên. Khoản vay đang được giải ngân.",
            "Cập nhật hợp đồng", "Hợp đồng cho vay của bạn đã đủ chữ ký."),

    // ---- Giải ngân ----
    LOAN_DISBURSED(DISBURSEMENT, "Khoản vay đã được giải ngân",
            "{amount} VND của khoản vay {loan} đã được chuyển vào ví FINORA của bạn.",
            "Giải ngân", "Khoản vay của bạn đã được giải ngân."),
    NOTES_ISSUED(DISBURSEMENT, "Khoản đầu tư đã bắt đầu",
            "Khoản vay {loan} đã giải ngân; Note của bạn đã được phát hành và bắt đầu tính lãi.",
            "Khoản đầu tư", "Khoản đầu tư của bạn đã bắt đầu."),

    // ---- Người vay: trả nợ và nhắc lịch ----
    REPAYMENT_RECORDED(REPAYMENT, "Đã ghi nhận khoản trả nợ",
            "Khoản thanh toán {amount} VND cho khoản vay {loan} đã được ghi nhận. {next}",
            "Trả nợ", "Khoản trả nợ của bạn đã được ghi nhận."),
    INSTALLMENT_PAYMENT_HELD(REPAYMENT, "Đã nhận tiền trả kỳ",
            "FINORA đã nhận {amount} VND cho kỳ đến hạn {dueDate} của khoản vay {loan}. Khoản này được ghi nhận đúng ngày đến hạn; bạn hủy được trước ngày đó.",
            "Trả nợ", "Đã nhận tiền trả kỳ của bạn."),
    INSTALLMENT_PAYMENT_CANCELLED(REPAYMENT, "Đã hủy khoản trả kỳ trước hạn",
            "Khoản trả kỳ đến hạn {dueDate} của khoản vay {loan} đã được hủy, tiền đã trả về ví của bạn.",
            "Trả nợ", "Khoản trả kỳ trước hạn đã được hủy."),
    INSTALLMENT_DUE_SOON(REPAYMENT, "Kỳ trả nợ sắp đến hạn",
            "Kỳ trả nợ của khoản vay {loan} đến hạn ngày {dueDate}{amountClause}. Vui lòng chuẩn bị số dư ví trước ngày đến hạn.",
            "Nhắc lịch trả nợ", "Kỳ trả nợ của bạn sắp đến hạn."),
    INSTALLMENT_DUE_TODAY(REPAYMENT, "Hôm nay là ngày đến hạn trả nợ",
            "Kỳ trả nợ của khoản vay {loan} đến hạn hôm nay ({dueDate}){amountClause}. Thanh toán trong hôm nay để không bị quá hạn.",
            "Nhắc lịch trả nợ", "Hôm nay là ngày đến hạn trả nợ của bạn."),
    LOAN_SETTLED(REPAYMENT, "Khoản vay đã tất toán",
            "Khoản vay {loan} đã hoàn tất nghĩa vụ thanh toán. Cảm ơn bạn đã đồng hành cùng FINORA.",
            "Trả nợ", "Khoản vay của bạn đã tất toán."),

    // ---- Người vay: quá hạn (D61) ----
    LOAN_OVERDUE(DELINQUENCY, "Khoản vay đã quá hạn",
            "Khoản vay {loan} đã quá hạn {dpd} ngày, số tiền quá hạn {overdue} VND. Vui lòng thanh toán để không bị chuyển nhóm nợ.",
            Push.DELINQUENCY_TITLE, Push.DELINQUENCY_BODY),
    OVERDUE_STAGE_CHANGED(DELINQUENCY, "Khoản vay chuyển nhóm nợ",
            "Khoản vay {loan} quá hạn {dpd} ngày và đã chuyển sang nhóm nợ {group} ({stage}). Số tiền quá hạn {overdue} VND.",
            Push.DELINQUENCY_TITLE, Push.DELINQUENCY_BODY),
    LOAN_DEFAULTED(DELINQUENCY, "Khoản vay đã chuyển nợ xấu",
            "Khoản vay {loan} quá hạn {dpd} ngày, đã chuyển nhóm nợ {group} và được xem là nợ xấu. Thanh toán toàn bộ phần quá hạn {overdue} VND để khoản vay trở lại bình thường.",
            Push.DELINQUENCY_TITLE, Push.DELINQUENCY_BODY),
    OVERDUE_CURED(DELINQUENCY, "Khoản vay đã hết quá hạn",
            "Bạn đã thanh toán đủ phần quá hạn của khoản vay {loan}. Khoản vay trở lại trạng thái bình thường.",
            Push.DELINQUENCY_TITLE, Push.DELINQUENCY_BODY),

    // ---- Người vay: cơ cấu ----
    RESCHEDULE_UNDER_INVESTOR_VOTE(RESTRUCTURING, "Yêu cầu cơ cấu đã qua thẩm định",
            "FINORA đã thẩm định đạt yêu cầu cơ cấu khoản vay {loan}. Yêu cầu đang chờ các nhà đầu tư biểu quyết; lịch trả nợ hiện tại vẫn áp dụng.",
            "Cơ cấu khoản vay", "Yêu cầu cơ cấu của bạn có cập nhật mới."),
    RESCHEDULE_APPROVED_BY_INVESTORS(RESTRUCTURING, "Nhà đầu tư đã đồng ý cơ cấu",
            "Các nhà đầu tư ({approvedInvestors}) đã đồng ý cơ cấu khoản vay {loan}. Lịch mới có hiệu lực sau khi hệ thống lõi xác nhận.",
            "Cơ cấu khoản vay", "Yêu cầu cơ cấu của bạn có cập nhật mới."),
    RESCHEDULE_REJECTED_BY_INVESTORS(RESTRUCTURING, "Yêu cầu cơ cấu không được chấp thuận",
            "Ít nhất một nhà đầu tư không đồng ý cơ cấu khoản vay {loan}. Lịch trả nợ hiện tại giữ nguyên.",
            "Cơ cấu khoản vay", "Yêu cầu cơ cấu của bạn có cập nhật mới."),
    LOAN_RESCHEDULED(RESTRUCTURING, "Lịch trả nợ mới đã có hiệu lực",
            "Khoản vay {loan} đã được cơ cấu; ngày đáo hạn mới là {maturityDate}. Xem lịch trả nợ mới trong ứng dụng.",
            "Cơ cấu khoản vay", "Lịch trả nợ của bạn đã được cập nhật."),

    // ---- Ví ----
    WITHDRAWAL_COMPLETED(WALLET, "Rút tiền thành công",
            "{amount} VND đã được chuyển tới tài khoản {bank} {account}.",
            "Rút tiền", "Yêu cầu rút tiền của bạn đã hoàn tất."),
    WITHDRAWAL_FAILED(WALLET, "Rút tiền không thành công",
            "Yêu cầu rút {amount} VND tới tài khoản {bank} {account} không thực hiện được. Vui lòng kiểm tra lịch sử ví và thử lại.",
            "Rút tiền", "Yêu cầu rút tiền của bạn chưa thực hiện được."),

    // ---- Nhà đầu tư: servicing từ Investment (giữ đúng changeType) ----
    REPAYMENT_CREDITED(REPAYMENT, "Đã nhận khoản trả nợ",
            "{loan} vừa phân bổ {amount} VND vào các Note của bạn.",
            "Khoản đầu tư", "Bạn vừa nhận một khoản trả nợ."),
    EARLY_SETTLEMENT(REPAYMENT, "Khoản vay đã tất toán sớm",
            "{loan} đã tất toán sớm; {amount} VND gốc, lãi và phí trả trước đã được phân bổ cho bạn.",
            "Khoản đầu tư", "Một khoản vay bạn đầu tư đã tất toán sớm."),
    SETTLED(REPAYMENT, "Khoản vay đã tất toán",
            "{loan} đã hoàn tất nghĩa vụ thanh toán.",
            "Khoản đầu tư", "Một khoản vay bạn đầu tư đã tất toán."),
    RESCHEDULED(RESTRUCTURING, "Lịch trả nợ đã được cơ cấu",
            "{loan} có ngày đáo hạn mới {maturityDate}.",
            "Khoản đầu tư", "Một khoản vay bạn đầu tư đã được cơ cấu."),
    RESCHEDULE_CONSENT_REQUESTED(RESTRUCTURING, "Cần bạn biểu quyết cơ cấu",
            "Người vay của {loan} đề nghị cơ cấu lịch trả nợ. Vui lòng biểu quyết trong mục Ví, Biểu quyết cơ cấu.",
            "Khoản đầu tư", "Có yêu cầu cơ cấu đang chờ bạn biểu quyết."),
    DELINQUENCY_CURED(DELINQUENCY, "Khoản vay đã khắc phục quá hạn",
            "{loan} đã trả đủ phần quá hạn và trở về nhóm nợ {group}.",
            Push.DELINQUENCY_TITLE, Push.INVESTOR_RISK_BODY),
    DELINQUENCY_CHANGED(DELINQUENCY, "Khoản vay đang quá hạn",
            "{loan} đang quá hạn {dpd} ngày, nhóm nợ {group}.",
            Push.DELINQUENCY_TITLE, Push.INVESTOR_RISK_BODY),
    BAD_DEBT_MILESTONE(DELINQUENCY, "Cập nhật rủi ro khoản vay",
            "{loan} đang ở nhóm nợ {group}, quá hạn {dpd} ngày.",
            Push.DELINQUENCY_TITLE, Push.INVESTOR_RISK_BODY),
    /** changeType mới mà Notification chưa biết: câu trung tính, không đoán là quá hạn (sửa B61). */
    INVESTOR_LOAN_UPDATE(GENERAL, "Cập nhật khoản đầu tư",
            "{loan} có cập nhật mới. Mở danh mục đầu tư để xem chi tiết.",
            "Khoản đầu tư", "Khoản đầu tư của bạn có cập nhật mới.");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

    private final NotificationCategory category;
    private final String title;
    private final String messagePattern;
    private final String pushTitle;
    private final String pushBody;

    NotificationTemplate(NotificationCategory category, String title, String messagePattern, String pushTitle,
            String pushBody) {
        this.category = category;
        this.title = title;
        this.messagePattern = messagePattern;
        this.pushTitle = pushTitle;
        this.pushBody = pushBody;
    }

    public NotificationCategory category() {
        return category;
    }

    /** Mọi mẫu hiện ở phiên bản 1; đổi câu chữ thì tách version theo từng mẫu. */
    public int version() {
        return 1;
    }

    public String title() {
        return title;
    }

    public String pushTitle() {
        return pushTitle;
    }

    public String pushBody() {
        return pushBody;
    }

    /** Câu mẫu thô; chỉ để test kiểm mọi chỗ trống đều được handler điền. */
    String messagePattern() {
        return messagePattern;
    }

    /**
     * Ghép giá trị vào câu trong app. Thiếu giá trị là lỗi lập trình (handler phải truyền đủ, kể cả giá trị dự phòng),
     * nên ném exception thay vì để lọt chữ {@code null} hay {@code {loan}} ra cho người dùng (sửa B63).
     */
    public String renderMessage(Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(messagePattern);
        StringBuilder message = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("Mẫu " + name() + " thiếu giá trị " + matcher.group(1));
            }
            matcher.appendReplacement(message, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(message);
        String rendered = message.toString().trim();
        return rendered.length() <= 1000 ? rendered : rendered.substring(0, 1000);
    }

    /** Câu chung chung trên màn hình khóa. */
    private static final class Push {
        static final String DELINQUENCY_TITLE = "FINORA";
        static final String DELINQUENCY_BODY = "Bạn có thông báo quan trọng về khoản vay. Mở FINORA để xem chi tiết.";
        static final String INVESTOR_RISK_BODY = "Có cập nhật về rủi ro khoản đầu tư. Mở FINORA để xem chi tiết.";
    }
}
