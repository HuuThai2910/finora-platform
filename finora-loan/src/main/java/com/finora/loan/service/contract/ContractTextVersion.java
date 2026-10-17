package com.finora.loan.service.contract;

import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.LoanApplication;
import java.util.Locale;

/**
 * Phiên bản văn bản hợp đồng đi theo cách tính lãi của khoản vay (Q3, D15).
 *
 * <p>Hồ sơ trên sản phẩm core V4 (lãi theo dư nợ và số ngày thực tế) nhận bộ văn bản mới; hồ sơ V1–V3 giữ đúng bộ văn bản
 * đang dùng để hợp đồng không hứa một cách tính khác cách Fineract của khoản vay đó thực sự áp dụng.</p>
 */
public record ContractTextVersion(
        String termsVersion,
        String documentVersion,
        String pdfVersion,
        boolean dailyInterest
) {

    public static final String DAILY_INTEREST_TERMS_VERSION = "LOAN_TERMS_V3";
    public static final String DAILY_INTEREST_DOCUMENT_VERSION = "CLICK_WRAP_TEXT_V4";
    public static final String DAILY_INTEREST_PDF_VERSION = "CONTRACT_PDF_V3";

    public static ContractTextVersion of(LoanApplication application, LoanContractProperties properties) {
        if (dailyInterest(application)) {
            return new ContractTextVersion(DAILY_INTEREST_TERMS_VERSION, DAILY_INTEREST_DOCUMENT_VERSION,
                    DAILY_INTEREST_PDF_VERSION, true);
        }
        return new ContractTextVersion(properties.termsVersion(), properties.documentVersion(),
                ContractPdfRenderer.DOCUMENT_VERSION, false);
    }

    public static boolean dailyInterest(LoanApplication application) {
        String version = application.getCoreConfigVersionSnapshot();
        return version != null && version.trim().toUpperCase(Locale.ROOT).endsWith("V4");
    }
}
