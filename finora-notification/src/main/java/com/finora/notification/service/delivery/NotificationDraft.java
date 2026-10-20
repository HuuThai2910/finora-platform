package com.finora.notification.service.delivery;

import com.finora.notification.service.template.NotificationTemplate;
import java.util.Map;

/**
 * Một thông báo cần tạo cho một người nhận, trước khi render và lưu.
 *
 * @param type              mã lưu ở hộp thư; thường là tên mẫu, riêng tin của Investment giữ nguyên {@code changeType}
 * @param values            giá trị cho các chỗ {@code {tên}} trong câu của mẫu
 * @param businessReference mã nghiệp vụ để app mở đúng màn (hiện là {@code loanApplicationId} hoặc mã lệnh rút tiền)
 * @param pushRequested     sự kiện này đáng push; preference, thiết bị và tuổi event còn quyết định có gửi không
 */
public record NotificationDraft(String recipientId, NotificationTemplate template, String type,
        Map<String, String> values, String businessReference, boolean pushRequested) {

    public static NotificationDraft of(String recipientId, NotificationTemplate template, Map<String, String> values,
            String businessReference) {
        return new NotificationDraft(recipientId, template, template.name(), values, businessReference, true);
    }

    public NotificationDraft withoutPush() {
        return new NotificationDraft(recipientId, template, type, values, businessReference, false);
    }
}
