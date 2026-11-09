package com.finora.notification.service.template.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.entity.LoanReference;
import com.finora.notification.service.template.NotificationText;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Định dạng giá trị đưa vào câu thông báo trong app. Không tính tiền: chỉ chuẩn hóa chuỗi decimal của event về scale 2
 * ({@code RoundingMode.HALF_UP}, VND) để app nhận ra và định dạng lại; ngày giờ hiển thị theo giờ Việt Nam.
 */
@Component
public class NotificationTextImpl implements NotificationText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final ZoneId zone;

    public NotificationTextImpl(NotificationProperties properties) {
        this.zone = properties.businessTimeZone();
    }

    @Override
    public String money(String decimal) {
        if (decimal == null || decimal.isBlank()) return null;
        try {
            return new BigDecimal(decimal.trim()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Số tiền trong event không đúng định dạng decimal", exception);
        }
    }

    @Override
    public String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Override
    public BigDecimal decimal(String value) {
        String normalized = money(value);
        return normalized == null ? null : new BigDecimal(normalized);
    }

    @Override
    public String moneyOr(String decimal, String fallback) {
        String value = money(decimal);
        return value == null ? fallback : value;
    }

    @Override
    public String date(LocalDate value) {
        return value == null ? "chưa xác định" : DATE.format(value);
    }

    @Override
    public String dateTime(Instant value) {
        return value == null ? "thời hạn ghi trên hợp đồng" : DATE_TIME.format(value.atZone(zone));
    }

    @Override
    public String loanLabel(Long loanApplicationId, LoanReference reference, String loanNumber, String contractNumber,
            String applicationNumber) {
        String label = first(loanNumber, reference == null ? null : reference.getLoanNumber(),
                reference == null ? null : reference.getContractCode(), contractNumber,
                reference == null ? null : reference.getApplicationCode(), applicationNumber);
        if (label == null && reference != null) {
            label = first(reference.getContractNumber(), reference.getApplicationNumber());
        }
        return label != null ? label : "khoản vay #" + loanApplicationId;
    }

    @Override
    public String applicationLabel(Long loanApplicationId, LoanReference reference, String applicationNumber) {
        String label = first(reference == null ? null : reference.getApplicationCode(), applicationNumber,
                reference == null ? null : reference.getApplicationNumber());
        return label != null ? label : "#" + loanApplicationId;
    }

    @Override
    public String contractLabel(LoanReference reference, String contractNumber) {
        String label = first(reference == null ? null : reference.getContractCode(), contractNumber,
                reference == null ? null : reference.getContractNumber());
        return label != null ? label : "của khoản vay";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
