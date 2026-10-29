package com.finora.investment.service.orderbook;

import com.finora.investment.dto.response.BookOrderResponse;
import com.finora.investment.dto.response.OrderBookPositionResponse;
import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import com.finora.investment.dto.response.OrderBookSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Đọc sổ lệnh: danh sách sổ, ảnh chụp độ sâu, lệnh và vị thế của người đang đăng nhập. Mọi danh
 * sách đều giới hạn số dòng; danh sách sổ gom truy vấn theo trang để không N+1.
 */
public interface OrderBookQueryService {

    /**
     * Các đợt gọi vốn còn Note lưu hành, kèm giá tốt nhất hai phía. Khoản vay mà {@code hiddenBorrowerId} là
     * người vay không hiện: người vay không được mua Note của chính mình.
     */
    Page<OrderBookSummaryResponse> listBooks(String hiddenBorrowerId, Pageable pageable);

    /** Ảnh chụp công khai của một sổ; sổ chưa có lệnh nào trả về hai phía rỗng. */
    OrderBookSnapshotResponse snapshot(Long listingId);

    Page<BookOrderResponse> myOrders(String investorId, boolean activeOnly, Pageable pageable);

    OrderBookPositionResponse myPosition(Long listingId, String investorId);
}
