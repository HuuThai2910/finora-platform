package com.finora.investment.service;

import com.finora.investment.domain.secondary.NoteListing;
import java.math.BigDecimal;

/**
 * Các transaction ngắn của luồng chợ thứ cấp, tách khỏi {@link SecondaryMarketService}.
 *
 * <p>Tách thành bean riêng là bắt buộc: Spring bọc transaction bằng proxy, nên một method
 * {@code @Transactional} được gọi qua {@code this} từ chính bean đó sẽ <em>không</em> mở
 * transaction nào. Cùng lý do với {@link OrderTransactionService}.</p>
 */
public interface SecondaryMarketTransactionService {

    /**
     * Bước 1 của việc mua — khóa tin đăng bán và kiểm mọi điều kiện, chưa chạm vào tiền.
     *
     * <p>Trả về bản ghi đã khóa để phần điều phối biết giá và người bán mà gọi Payment. Khóa
     * được nhả khi transaction này kết thúc, nên giữa bước này và bước ghi nhận vẫn có khe hở —
     * khe đó được bịt bằng ràng buộc một tin chỉ sinh một lần chuyển nhượng.</p>
     */
    NoteListing lockAndValidateForPurchase(String listingReference, String buyerId);

    /**
     * Bước 3 của việc mua — đổi chủ sở hữu Note, đóng tin và ghi lịch sử chuyển nhượng.
     *
     * <p>Ba việc trong cùng một transaction: nếu Note đã đổi chủ mà tin chưa đóng thì Note đó
     * còn treo bán trong khi đã thuộc người khác.</p>
     */
    NoteListing recordPurchase(
            Long listingId,
            String buyerId,
            BigDecimal price,
            BigDecimal platformFee,
            String paymentReference);
}
