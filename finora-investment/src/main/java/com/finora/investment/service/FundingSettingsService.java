package com.finora.investment.service;

import com.finora.investment.dto.request.UpdateFundingSettingsRequest;
import com.finora.investment.dto.response.FundingSettingsResponse;
import java.math.BigDecimal;

/**
 * Tham số gọi vốn toàn sàn mà quản trị đổi được lúc chạy.
 *
 * <p>Thay cho việc sửa {@code application.yml} rồi khởi động lại service. Giá trị trong
 * file vẫn còn, nhưng chỉ dùng làm mặc định khi bảng cấu hình chưa có dữ liệu.</p>
 */
public interface FundingSettingsService {

    FundingSettingsResponse current();

    FundingSettingsResponse update(UpdateFundingSettingsRequest request);

    /**
     * Mệnh giá Note đang áp dụng, dùng khi tạo listing mới.
     *
     * <p>Tách riêng khỏi {@link #current()} vì nơi gọi chỉ cần một con số, không cần
     * dựng cả DTO phản hồi.</p>
     */
    BigDecimal currentNoteDenomination();

    BigDecimal currentMinInvestment();

    int currentFundingDays();
}
