package com.finora.investment.service;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.response.OrderIncidentResponse;

/**
 * Màn sự cố của admin cho lệnh góp vốn chưa khớp với ví (R3): lệnh chờ giữ tiền đã thử lại nhiều lần, khoản giữ chưa nhả
 * được, hoặc nhả bị dừng vì Payment trả lỗi chắc chắn. "Thử lại" chỉ hẹn lượt kế tiếp của worker ngay, không tự gọi Payment.
 */
public interface OrderIncidentService {

    /**
     * Từ lần thử lỗi thứ 3 (khoảng 3–4 phút sau lần đầu) lệnh hiện cho vận hành; trước đó worker tự xử lý. Gauge
     * {@code finora.operations.incidents} (W3E) đếm theo cùng ngưỡng để số trên dashboard khớp màn sự cố.
     */
    int ATTEMPT_THRESHOLD = 3;

    PageResponse<OrderIncidentResponse> list(int page, int size);

    OrderIncidentResponse retry(String orderReference);
}
