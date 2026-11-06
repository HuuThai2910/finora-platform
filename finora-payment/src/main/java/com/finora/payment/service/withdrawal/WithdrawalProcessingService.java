package com.finora.payment.service.withdrawal;

import com.finora.payment.integration.payout.client.PayoutProvider;

import java.util.List;
import java.util.UUID;

/**
 * Đưa lệnh rút từ {@code REQUESTED} tới kết quả cuối (R4, W3B). Quy tắc tiền (D41, D42):
 * <ul>
 *   <li>Không rõ provider đã chi hay chưa (timeout, worker chết giữa chừng, mất callback) thì chỉ tra cứu theo mã lệnh,
 *       không gửi lệnh chi mới.</li>
 *   <li>Provider khẳng định chưa từng nhận lệnh mới gửi lại, với đúng mã lệnh cũ.</li>
 *   <li>Thất bại chắc chắn hoàn tiền trong cùng transaction chuyển {@code FAILED}.</li>
 * </ul>
 */
public interface WithdrawalProcessingService {

    /** Lệnh rút chưa có kết quả cuối đã đến lượt xử lý (tối đa 20 lệnh mỗi vòng). */
    List<Long> dueIds();

    /** Một bước của worker cho một lệnh: nhận việc, gửi hoặc tra cứu provider ngoài transaction rồi áp kết quả. */
    void execute(Long id);

    /**
     * Khóa lệnh và quyết định bước kế tiếp; {@code null} khi lệnh chưa đến hạn hoặc không còn việc. Lệnh còn
     * {@code PROCESSING} khi đến hạn nghĩa là lease đã hết: chuyển đối soát và chỉ tra cứu theo mã lệnh. Phải gọi trong
     * transaction.
     */
    Work claim(Long id);

    /**
     * Callback của provider payout giả lập (D43). Chỉ tồn tại khi provider là {@code mock}; chữ ký sai trả 401 và không
     * đổi gì; callback lặp không ghi/hoàn hai lần; callback mâu thuẫn với kết quả đã chốt trả 409 để vận hành kiểm.
     */
    void handleMockCallback(String body, String signature);

    /** Bước kế tiếp của lệnh rút: gửi lệnh chi hoặc chỉ tra cứu kết quả. */
    enum Step { SUBMIT, LOOKUP }

    /** Việc worker đã nhận cho một lệnh rút; {@code command} chỉ có ở bước {@link Step#SUBMIT}. */
    record Work(Long id, UUID withdrawalId, Step step, PayoutProvider.PayoutCommand command) {
    }
}
