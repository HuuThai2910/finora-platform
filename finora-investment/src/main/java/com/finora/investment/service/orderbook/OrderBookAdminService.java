package com.finora.investment.service.orderbook;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.dto.request.AdminTradeFilter;
import com.finora.investment.dto.response.AdminNoteOwnershipResponse;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Đọc chợ Notes cho màn quản trị: các lần khớp kèm hai bên mua bán, trạng thái thanh toán và giấy xác nhận, cùng
 * dải số liệu tổng. Chỉ đọc — quản trị không đặt hay huỷ lệnh thay nhà đầu tư.
 */
public interface OrderBookAdminService {

    /** Mọi lần khớp (hoặc một trạng thái thanh toán), mới nhất trước. */
    default Page<AdminTradeResponse> trades(SettlementStatus status, Pageable pageable) {
        return trades(status, null, pageable);
    }

    /**
     * Như trên, thêm ô tìm {@code q}: mã lần khớp, mã hồ sơ ({@code HS-…}/{@code LA-…}) hoặc mã hợp đồng
     * ({@code HĐ-…}/{@code HD-…}/{@code LC-…}) của khoản vay, khớp một phần; rỗng thì như trên.
     */
    default Page<AdminTradeResponse> trades(SettlementStatus status, String q, Pageable pageable) {
        return searchTrades(new AdminTradeFilter(null, status, null, q, null, null, null, null, null), pageable);
    }

    /**
     * Lần khớp theo bộ lọc của Chợ chuyển nhượng (sổ, chặng gộp, ngày khớp, số tiền, mã, tên hai bên), mới nhất trước.
     * Tên hai bên tra ID qua User; không ai khớp tên thì trang rỗng, User lỗi thì 503 {@code USER_SEARCH_UNAVAILABLE}.
     */
    Page<AdminTradeResponse> searchTrades(AdminTradeFilter filter, Pageable pageable);

    /** Một lần khớp theo mã; 404 {@code TRADE_NOT_FOUND} khi không có. */
    AdminTradeResponse trade(String tradeReference);

    OrderBookAdminSummaryResponse summary();

    /**
     * Gửi thanh toán lại một lần khớp "Cần đối soát" ({@code FAILED}): đưa về hàng chờ để worker gọi Payment lần nữa với
     * cùng mã (idempotent). Không đảo chủ Note, không tự chuyển tiền ngoài lời gọi Payment đó.
     */
    AdminTradeResponse requeueSettlement(String tradeReference);

    /**
     * Các Note đã chuyển trong một lần khớp, kèm người đang giữ, giá đang rao bán (nếu có) và toàn bộ lịch sử chuyển
     * nhượng của từng Note.
     */
    List<AdminNoteOwnershipResponse> tradeNotes(String tradeReference);
}
