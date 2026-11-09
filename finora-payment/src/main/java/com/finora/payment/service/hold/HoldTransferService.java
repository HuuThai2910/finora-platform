package com.finora.payment.service.hold;

import com.finora.payment.dto.request.CreateHoldRequest;
import com.finora.payment.dto.request.SettleHoldRequest;
import com.finora.payment.dto.request.TransferRequest;
import com.finora.payment.dto.response.HoldResponse;
import com.finora.payment.dto.response.TransferResponse;

/**
 * Giữ, nhả và thanh toán từ khoản tiền giữ trong ví nhà đầu tư, và chuyển tiền giữa hai ví nhà đầu tư, cho Investment
 * (góp vốn, sổ lệnh Notes, chuyển nhượng Note). Mọi thay đổi số dư đi qua ledger.
 */
public interface HoldTransferService {

    /**
     * Client role của service account finora-investment, dùng cho worker của Investment: giữ và nhả tiền
     * thay nhà đầu tư khi không có JWT của họ. Chỉ áp dụng cho hold/release, không cho transfer.
     */
    String ON_BEHALF_AUTHORITY = "payment:hold:on_behalf";

    /**
     * Giữ tiền trong ví nhà đầu tư cho một lệnh đầu tư (chuyển từ số dư khả dụng sang số dư giữ). Idempotent theo mã
     * lệnh: gọi lại cùng nội dung trả khoản giữ cũ, khác nội dung thì {@code 409 PAYMENT_HOLD_IDEMPOTENCY_CONFLICT}.
     */
    HoldResponse hold(CreateHoldRequest request);

    /**
     * R3: đọc khoản giữ theo mã lệnh mà không đổi gì, để Investment đối chiếu lệnh chờ giữ tiền trước khi gửi lại lời
     * gọi giữ tiền. Service account của Investment đọc được mọi khoản; nhà đầu tư chỉ đọc khoản của mình, khoản của
     * người khác trả 404 như không tồn tại.
     */
    HoldResponse findByOrderReference(String orderReference);

    /**
     * Nhả phần còn giữ của khoản giữ về số dư khả dụng; khoản giữ đã vào giải ngân trả
     * {@code 409 PAYMENT_HOLD_NOT_RELEASABLE}.
     */
    HoldResponse release(String holdReference, String orderReference);

    /**
     * Thanh toán một lần khớp trên sổ lệnh Notes từ khoản giữ của người mua: trừ phần giữ, cộng
     * người bán phần sau phí, phí vào {@code PLATFORM_FEE}. Ledger và khoản giữ đổi trong cùng một
     * transaction.
     *
     * <p>Chỉ service account của finora-investment gọi được: thanh toán chạy trong worker sau khi
     * khớp, lúc đó người mua không có mặt (lần khớp có thể do người bán kích hoạt). Gọi lại cùng
     * {@code settlementReference} trả kết quả cũ, không chuyển tiền và không trừ khoản giữ lần hai.</p>
     */
    TransferResponse settleFromHold(String holdReference, SettleHoldRequest request);

    /**
     * Chuyển tiền giữa hai ví nhà đầu tư khi chuyển nhượng Note: trừ người mua, cộng người bán phần sau phí, phí vào
     * {@code PLATFORM_FEE}. Idempotent theo mã chuyển tiền.
     */
    TransferResponse transfer(TransferRequest request);
}
