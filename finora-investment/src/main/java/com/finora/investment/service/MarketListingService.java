package com.finora.investment.service;

import com.finora.investment.dto.request.CreateListingRequest;
import java.math.BigDecimal;
import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.response.FundingProgressResponse;
import com.finora.investment.dto.response.ListingInvestorResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import java.util.List;

/**
 * Sàn gọi vốn: đưa khoản vay lên sàn, tra cứu và theo dõi tiến độ.
 */
public interface MarketListingService {

    /**
     * Tạo listing từ khoản vay lấy ở finora-loan.
     */
    MarketListingResponse createListingFrom(CreateListingRequest request);

    /** Danh sách khoản vay đang mở gọi vốn, có lọc và phân trang. */
    /**
     * Tìm khoản vay trên sàn.
     *
     * <p>{@code status} để trống thì mặc định chỉ trả khoản đang mở, vì app nhà đầu tư
     * chỉ đặt lệnh được vào đó. Màn quản trị truyền trạng thái cụ thể — hoặc
     * {@code ALL} để thấy mọi khoản — vì thao tác khóa vốn và phát hành Note chỉ áp
     * dụng cho khoản đã gọi đủ vốn.</p>
     */
    PageResponse<MarketListingResponse> search(
            String status,
            String creditGrade,
            BigDecimal minAnnualRate,
            Integer maxTermMonths,
            int page,
            int size
    );

    MarketListingResponse detail(Long listingId);

    /**
     * Tiến độ gọi vốn của một khoản vay.
     *
     * <p>Số nhà đầu tư và số Note được tính từ danh sách commitment đã tải sẵn, không truy
     * vấn thêm cho từng dòng — tránh N+1 (performance-data-access.md).</p>
     */
    FundingProgressResponse progress(Long listingId);

    /**
     * Đóng các listing đã hết hạn mà chưa gọi đủ vốn.
     *
     * <p>Xử lý theo lô có giới hạn để một lần chạy không quét toàn bộ bảng.</p>
     *
     * @return số listing vừa được đóng
     */
    int closeExpiredListings(int batchSize);

    /**
     * Danh sách nhà đầu tư đã góp vốn vào một khoản vay.
     *
     * <p>Dành cho quản trị: khác {@code portfolio()} của nhà đầu tư ở chỗ trả về
     * {@code investorId}, nên chỉ lộ qua endpoint {@code /admin/**}.</p>
     *
     * <p>Trả cả phần vốn đã hủy để quản trị thấy lịch sử đầy đủ; trạng thái nằm trong
     * từng dòng nên phía giao diện tự phân biệt được.</p>
     */
    List<ListingInvestorResponse> investorsOf(Long listingId);
}
