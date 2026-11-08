package com.finora.payment.service.bankaccount;

import com.finora.payment.dto.request.BankAccountInquiryRequest;
import com.finora.payment.dto.request.ConfirmBankLinkRequest;
import com.finora.payment.dto.request.StartBankLinkRequest;
import com.finora.payment.dto.response.BankAccountInquiryResponse;
import com.finora.payment.dto.response.BankAccountResponse;
import com.finora.payment.dto.response.BankLinkChallengeResponse;
import com.finora.payment.dto.response.BankResponse;

import java.util.List;
import java.util.UUID;

/**
 * Liên kết tài khoản ngân hàng nhận tiền rút (BL-001): chọn ngân hàng → tra cứu chủ tài khoản → xác nhận PIN → ngân hàng
 * gửi OTP → nhập OTP → tài khoản được liên kết. Chỉ người đã eKYC mới liên kết được và chỉ tài khoản đứng tên chính mình.
 */
public interface BankAccountService {

    List<BankResponse> banks();

    /** Tài khoản đang liên kết của người dùng, mặc định trước. */
    List<BankAccountResponse> list();

    BankAccountInquiryResponse inquire(BankAccountInquiryRequest request);

    /** Gửi lại cùng {@code idempotencyKey} trả đúng yêu cầu cũ, không gửi thêm OTP. */
    BankLinkChallengeResponse startLink(StartBankLinkRequest request, String idempotencyKey);

    BankLinkChallengeResponse resendOtp(UUID linkRequestId);

    BankAccountResponse confirm(UUID linkRequestId, ConfirmBankLinkRequest request);

    BankAccountResponse setDefault(UUID bankAccountId);

    void remove(UUID bankAccountId);

    /** Thông tin tài khoản đã liên kết để tạo lệnh rút; số tài khoản ở dạng rõ chỉ sống trong bộ nhớ của request. */
    LinkedDestination destinationFor(String ownerId, UUID bankAccountId);

    record LinkedDestination(String bankCode, String accountNumber, String accountHolderName) {
    }
}
