package com.finora.loan.service.contract;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Sinh PDF A4 phía server từ đúng snapshot Contract.
 *
 * <p>PDF được lưu ngay sau khi sinh; endpoint tải chỉ trả lại bytes đã lưu để hash không thay đổi
 * theo máy, font hoặc thời điểm render.</p>
 */
public interface ContractPdfRenderer {

    /** Bản PDF cho khoản vay core V1–V3; core V4 dùng bản tính lãi theo ngày ({@code CONTRACT_PDF_V3}). */
    String DOCUMENT_VERSION = "CONTRACT_PDF_V2";

    /** Hậu tố version cho bản ký PAdES (R5): cùng điều khoản, khác trang xác nhận ký số. */
    String PADES_VERSION_SUFFIX = "_PADES";

    /** Trang biên nhận chữ ký số tách rời của hợp đồng PAdES (Q9, D45). */
    String RECEIPT_DOCUMENT_VERSION = "PADES_RECEIPT_V1";

    /** PDF để người vay đọc và ký của hợp đồng một bên. */
    ContractPdfArtifact renderSignable(
            String contractNumber,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            Instant expiresAt
    );

    /** PDF để các bên ký của hợp đồng nhiều bên (bản không ký PAdES). */
    ContractPdfArtifact renderFundedSignable(
            String contractNumber,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            Instant expiresAt,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash
    );

    /**
     * Bản PAdES (R5, Q9) giữ nguyên điều khoản của phiên bản gốc, chỉ đổi trang xác nhận: mô tả thứ tự ký số ẩn và chỉ
     * ra trang biên nhận nằm ngoài tệp. Đổi văn bản nên mang version riêng ({@code ..._PADES}).
     */
    ContractPdfArtifact renderFundedSignable(
            String contractNumber,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String termsVersion,
            Instant expiresAt,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash,
            boolean pades
    );

    /**
     * Trang biên nhận chữ ký số (Q9, D45): lập sau chữ ký cuối nên là tài liệu riêng, không nằm trong phần các bên đã
     * ký; dẫn chiếu SHA-256 của SIGNABLE và của tệp PAdES đã ký đủ, liệt kê từng chữ ký đã kiểm theo thứ tự.
     */
    ContractPdfArtifact renderSignatureReceipt(ContractSignatureReceipt receipt);

    /** Receipt sau khi người vay xác nhận hợp đồng một bên. */
    ContractPdfArtifact renderSignedReceipt(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String signedDocumentHash
    );

    /**
     * Receipt sau khi đủ chữ ký hợp đồng nhiều bên, kèm phụ lục bằng chứng chữ ký các bên
     * cho vay.
     */
    ContractPdfArtifact renderSignedReceipt(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            String signedDocumentHash,
            List<ContractParty> parties
    );
}
