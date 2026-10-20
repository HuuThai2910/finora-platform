package com.finora.notification.service.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/**
 * D63: nội dung push hiện trên màn hình khóa không được có số tiền, tên hay mã khoản vay; câu trong app không được lọt
 * {@code null} hay chỗ trống chưa điền (B63).
 */
class NotificationTemplateTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

    @ParameterizedTest
    @EnumSource(NotificationTemplate.class)
    void pushTextHasNoDigitsAmountsOrPlaceholders(NotificationTemplate template) {
        for (String text : new String[] {template.pushTitle(), template.pushBody()}) {
            assertThat(text).isNotBlank().doesNotContainPattern("\\d").doesNotContain("{").doesNotContain("VND")
                    .doesNotContain("LN-").doesNotContain("LA-").doesNotContain("LC-");
        }
        // Vừa cột notification_push_deliveries.title/body.
        assertThat(template.pushTitle().length()).isLessThanOrEqualTo(100);
        assertThat(template.pushBody().length()).isLessThanOrEqualTo(200);
        assertThat(template.title().length()).isLessThanOrEqualTo(160);
    }

    @ParameterizedTest
    @EnumSource(NotificationTemplate.class)
    void everyPlaceholderIsFilledWhenTheHandlerPassesAllValues(NotificationTemplate template) {
        String rendered = template.renderMessage(valuesFor(template));

        assertThat(rendered).doesNotContain("{").doesNotContain("}").doesNotContain("null").isNotBlank();
    }

    @Test
    void missingValueFailsLoudlyInsteadOfShowingAPlaceholderToTheUser() {
        assertThatThrownBy(() -> NotificationTemplate.LOAN_DISBURSED.renderMessage(Map.of("loan", "LN-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void inAppAmountKeepsTheDecimalVndFormatThatTheMobileMapperReformats() {
        String message = NotificationTemplate.REPAYMENT_CREDITED.renderMessage(
                Map.of("loan", "LN-2026-000001", "amount", "842500.00"));

        assertThat(message).isEqualTo("LN-2026-000001 vừa phân bổ 842500.00 VND vào các Note của bạn.");
    }

    @Test
    void delinquencyPushIsFullyGenericBecauseDebtStatusIsSensitive() {
        assertThat(NotificationTemplate.LOAN_DEFAULTED.pushBody()).doesNotContain("nợ xấu").doesNotContain("quá hạn");
        assertThat(NotificationTemplate.LOAN_OVERDUE.pushBody()).doesNotContain("quá hạn");
    }

    private static Map<String, String> valuesFor(NotificationTemplate template) {
        Map<String, String> values = new HashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(template.messagePattern());
        while (matcher.find()) values.put(matcher.group(1), "x");
        return values;
    }
}
