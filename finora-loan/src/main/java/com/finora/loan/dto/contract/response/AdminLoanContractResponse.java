package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.enums.ContractDeclineReasonCode;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.enums.SignatureProviderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Hợp đồng vay cho màn quản trị (trang Gọi vốn): hạn ký, mã băm, nhà cung cấp chữ ký số và tình hình ký của từng bên.
 *
 * <p>Khác API của người vay/nhà đầu tư: không kèm nội dung văn bản, lịch trả hay tham chiếu phiên ký SmartCA; chỉ những
 * gì người quản trị cần để theo dõi tiến độ ký. Tải PDF qua {@code /admin/loan-contracts/{contractNumber}/document}.</p>
 *
 * @param contractCode       mã hiển thị {@code HĐ-...}; {@code contractNumber} chỉ là khóa tra cứu API
 * @param loanNumber         mã khoản vay sau giải ngân, để đọc các kỳ trả; null khi chưa giải ngân
 * @param documentHash       SHA-256 của văn bản hợp đồng hiện hành (đổi khi phát hành lại sau gọi vốn bổ sung)
 * @param pdfDocumentHash    SHA-256 của tệp PDF tải tại {@code /document}: bản để ký khi còn chờ ký, bản đủ chữ ký sau
 *                           khi hoàn tất; null khi chưa dựng PDF
 * @param signatureProvider  nhà cung cấp chữ ký số: của bên đã ký nếu có, không thì cấu hình hiện hành của Loan
 * @param issuedAt           lần phát hành văn bản hiện hành (gồm phát hành lại); lần đầu khi không có lịch sử trạng thái
 * @param expiresAt          hạn ký của văn bản hiện hành
 * @param effectiveAt        thời điểm hợp đồng có hiệu lực; null khi chưa
 * @param parties            nhà đầu tư trước (số tiền lớn trước), người vay cuối cùng vì ký sau cùng
 */
public record AdminLoanContractResponse(
        String contractNumber,
        String contractCode,
        String loanNumber,
        LoanContractStatus status,
        boolean multiParty,
        BigDecimal principalAmount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        String documentHash,
        String pdfDocumentHash,
        SignatureProviderType signatureProvider,
        Instant issuedAt,
        Instant expiresAt,
        Instant effectiveAt,
        Instant declinedAt,
        ContractDeclineReasonCode declineReasonCode,
        List<AdminContractPartyResponse> parties
) {
}
