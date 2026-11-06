package com.finora.loan.service.application;

import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;

/**
 * Hồ sơ người vay cho nhà đầu tư trước khi góp vốn (NĐ 94/2025 Điều 22 khoản 3 và khoản 8 điểm c:
 * cung cấp đầy đủ thông tin khoản vay trước khi khách hàng giao kết).
 *
 * <p>Chỉ hồ sơ đã được đưa lên sàn ({@code fundingStatus != null}) mới xem được. Hồ sơ chưa lên sàn trả
 * cùng lỗi 404 như hồ sơ không tồn tại, để không dò được hồ sơ đang thẩm định. Hồ sơ đã đủ vốn vẫn xem
 * được vì Note của nó còn được mua bán trên chợ thứ cấp.</p>
 */
public interface InvestorBorrowerProfileService {

    /**
     * Hồ sơ người vay của khoản vay trên sàn; chỉ tài khoản nhà đầu tư được xem
     * ({@code 403 INVESTOR_ROLE_REQUIRED}).
     */
    InvestorBorrowerProfileResponse profile(String applicationNumber);
}
