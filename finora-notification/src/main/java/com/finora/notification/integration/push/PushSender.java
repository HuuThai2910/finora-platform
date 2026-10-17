package com.finora.notification.integration.push;

import com.finora.notification.config.PushProviderType;
import java.util.List;
import java.util.Map;

/**
 * Biên tích hợp với nhà cung cấp push (D62). Hai cài đặt: {@link LoggingPushSender} (giả lập, mặc định, không có thiết
 * bị thật) và {@link ExpoPushSender} (Expo Push API). Mọi lời gọi chạy ngoài transaction database; kết quả được ghi bằng
 * transaction riêng nên provider lỗi không giữ khóa hay làm hỏng dữ liệu thông báo.
 */
public interface PushSender {

    PushProviderType provider();

    /**
     * Gửi một lô (tối đa 100 theo giới hạn Expo). Không ném exception: lỗi mạng/HTTP được trả thành
     * {@link PushOutcome#RETRYABLE_FAILURE} cho từng thông điệp.
     *
     * @return ticket cùng thứ tự và cùng số lượng với {@code messages}
     */
    List<PushTicket> send(List<PushMessage> messages);

    /**
     * Đọc receipt theo ticket ID (tối đa 1000). ID chưa có receipt thì không có trong map; lỗi gọi API trả map rỗng để
     * lần sau đọc lại.
     */
    Map<String, PushReceipt> receipts(List<String> ticketIds);
}
