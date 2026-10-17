package com.finora.notification.service.template;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.LoanReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Định dạng giá trị đưa vào câu thông báo trong app. Không tính tiền: chỉ chuẩn hóa chuỗi decimal của event về scale 2
 * ({@code RoundingMode.HALF_UP}, VND) để app nhận ra và định dạng lại; ngày giờ hiển thị theo giờ Việt Nam.
 */
@Component
public class NotificationText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final ZoneId zone;

    public NotificationText(NotificationProperties properties) {
        this.zone = properties.businessTimeZone();
    }

    /** {@code "1726000"} → {@code "1726000.00"}; thiếu/sai định dạng trả {@code null} để caller chọn câu không có số. */
    public String money(String decimal) {
        if (decimal == null || decimal.isBlank()) return null;
        try {
            return new BigDecimal(decimal.trim()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Số tiền trong event không đúng định dạng decimal", exception);
        }
    }

    public String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Chuỗi decimal của event thành {@link BigDecimal} scale 2; thiếu thì {@code null}, sai định dạng là lỗi contract. */
    public BigDecimal decimal(String value) {
        String normalized = money(value);
        return normalized == null ? null : new BigDecimal(normalized);
    }

    /** Như {@link #money(String)} nhưng luôn có chữ thay thế để câu không bị khuyết. */
    public String moneyOr(String decimal, String fallback) {
        String value = money(decimal);
        return value == null ? fallback : value;
    }

    public String date(LocalDate value) {
        return value == null ? "chưa xác định" : DATE.format(value);
    }

    public String dateTime(Instant value) {
        return value == null ? "thời hạn ghi trên hợp đồng" : DATE_TIME.format(value.atZone(zone));
    }

    /**
     * Nhãn khoản vay dễ nhận: mã khoản vay servicing → số hợp đồng → mã hồ sơ → "khoản vay #id". Giá trị từ event được
     * ưu tiên hơn bảng tra vì là dữ liệu của chính lần thay đổi đó.
     */
    public String loanLabel(Long loanApplicationId, LoanReference reference, String loanNumber, String contractNumber,
            String applicationNumber) {
        String label = first(loanNumber, contractNumber, applicationNumber);
        if (label == null && reference != null) {
            label = first(reference.getLoanNumber(), reference.getContractNumber(), reference.getApplicationNumber());
        }
        return label != null ? label : "khoản vay #" + loanApplicationId;
    }

    public String applicationLabel(Long loanApplicationId, LoanReference reference, String applicationNumber) {
        String label = first(applicationNumber, reference == null ? null : reference.getApplicationNumber());
        return label != null ? label : "#" + loanApplicationId;
    }

    public String contractLabel(LoanReference reference, String contractNumber) {
        String label = first(contractNumber, reference == null ? null : reference.getContractNumber());
        return label != null ? label : "của khoản vay";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
