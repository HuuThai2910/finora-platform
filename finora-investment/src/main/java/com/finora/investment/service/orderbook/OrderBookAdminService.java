package com.finora.investment.service.orderbook;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.dto.response.AdminNoteOwnershipResponse;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Đọc chợ Notes cho màn quản trị: các lần khớp kèm hai bên mua bán và trạng thái thanh toán, cùng
 * dải số liệu tổng. Chỉ đọc — quản trị không đặt hay huỷ lệnh thay nhà đầu tư.
 */
public interface OrderBookAdminService {

    Page<AdminTradeResponse> trades(SettlementStatus status, Pageable pageable);

    /**
     * Như trên, thêm ô tìm {@code q}: mã lần khớp, mã hồ sơ ({@code HS-…}/{@code LA-…}) hoặc mã hợp đồng
     * ({@code HĐ-…}/{@code HD-…}/{@code LC-…}) của khoản vay, khớp một phần; rỗng thì như trên.
     */
    Page<AdminTradeResponse> trades(SettlementStatus status, String q, Pageable pageable);

    OrderBookAdminSummaryResponse summary();

    /**
     * Gửi thanh toán lại một lần khớp "Cần đối soát" ({@code FAILED}): đưa về hàng chờ để worker gọi Payment lần nữa với
     * cùng mã (idempotent). Không đảo chủ Note, không tự chuyển tiền ngoài lời gọi Payment đó.
     */
    AdminTradeResponse requeueSettlement(String tradeReference);

    /** Các Note đã chuyển trong một lần khớp, kèm người đang giữ và toàn bộ lịch sử chuyển nhượng của từng Note. */
    java.util.List<AdminNoteOwnershipResponse> tradeNotes(String tradeReference);
}
