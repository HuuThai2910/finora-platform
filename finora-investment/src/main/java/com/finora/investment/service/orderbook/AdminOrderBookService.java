package com.finora.investment.service.orderbook;

import com.finora.investment.dto.request.AdminOrderBookFilter;
import com.finora.investment.dto.response.AdminListingNoteResponse;
import com.finora.investment.dto.response.AdminOrderBookDetailResponse;
import com.finora.investment.dto.response.AdminOrderBookResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Sổ lệnh nhìn từ Chợ chuyển nhượng quản trị: danh sách sổ có lọc và sắp xếp, chi tiết một sổ (khoản vay, trả nợ, số
 * liệu chuyển nhượng) và các phần vốn của khoản vay. Chỉ đọc.
 */
public interface AdminOrderBookService {

    /**
     * Các khoản vay còn phần vốn lưu hành, lọc theo mã/tên người vay, trạng thái sổ, hạng, lãi suất và sắp xếp theo
     * {@link AdminOrderBookFilter.Sort}. Lọc ở server trên toàn bộ tập khoản đang trả nợ rồi mới cắt trang.
     */
    Page<AdminOrderBookResponse> books(AdminOrderBookFilter filter, Pageable pageable);

    /** Chi tiết một sổ; 404 {@code ORDER_BOOK_NOT_FOUND} khi khoản vay chưa phát hành phần vốn. */
    AdminOrderBookDetailResponse book(Long listingId);

    /**
     * Phần vốn của khoản vay theo thứ tự phát hành; {@code q} khớp một phần mã phần vốn hoặc họ tên người đang nắm giữ.
     */
    Page<AdminListingNoteResponse> notes(Long listingId, String q, Pageable pageable);
}
