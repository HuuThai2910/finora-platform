package com.finora.notification.service.template;

import com.finora.notification.domain.entity.LoanReference;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Định dạng giá trị đưa vào câu thông báo trong app. Không tính tiền: chỉ chuẩn hóa chuỗi decimal của event về scale 2
 * ({@code RoundingMode.HALF_UP}, VND) để app nhận ra và định dạng lại; ngày giờ hiển thị theo giờ Việt Nam.
 */
public interface NotificationText {

    /** {@code "1726000"} → {@code "1726000.00"}; thiếu/sai định dạng trả {@code null} để caller chọn câu không có số. */
    String money(String decimal);

    String money(BigDecimal amount);

    /** Chuỗi decimal của event thành {@link BigDecimal} scale 2; thiếu thì {@code null}, sai định dạng là lỗi contract. */
    BigDecimal decimal(String value);

    /** Như {@link #money(String)} nhưng luôn có chữ thay thế để câu không bị khuyết. */
    String moneyOr(String decimal, String fallback);

    String date(LocalDate value);

    String dateTime(Instant value);

    /**
     * Nhãn khoản vay dễ nhận: mã khoản vay servicing → số hợp đồng → mã hồ sơ → "khoản vay #id". Giá trị từ event được
     * ưu tiên hơn bảng tra vì là dữ liệu của chính lần thay đổi đó.
     */
    String loanLabel(Long loanApplicationId, LoanReference reference, String loanNumber, String contractNumber,
            String applicationNumber);

    String applicationLabel(Long loanApplicationId, LoanReference reference, String applicationNumber);

    String contractLabel(LoanReference reference, String contractNumber);
}
