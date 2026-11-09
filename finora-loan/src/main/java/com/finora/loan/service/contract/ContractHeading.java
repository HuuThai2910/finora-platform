package com.finora.loan.service.contract;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.model.BorrowerIdentity;

import java.util.Objects;

/**
 * Phần định danh in ở đầu văn bản hợp đồng: số hợp đồng tra cứu, mã hiển thị và danh tính bên vay.
 *
 * <p>{@code borrower == null} là hợp đồng lập trước khi Loan chụp danh tính (V39): văn bản đã lưu của chúng ghi số
 * {@code LC-} và mã tham chiếu người vay, nên biên nhận render lại cũng giữ đúng cách ghi đó để khớp bản các bên đã đọc.
 * Hợp đồng mới luôn có danh tính (kể cả khi User chưa có họ tên) và in mã hiển thị {@code HĐ-}/{@code HS-}.</p>
 *
 * @param contractNumber mã tra cứu {@code LC-...}, khóa API/event
 * @param contractCode   mã hiển thị {@code HĐ-yyyyMMdd-XXXX}
 * @param borrower       danh tính bên vay đã chụp, {@code null} với hợp đồng cũ
 */
public record ContractHeading(String contractNumber, String contractCode, BorrowerIdentity borrower) {

    public ContractHeading {
        Objects.requireNonNull(contractNumber, "contractNumber");
    }

    /** Định danh của một hợp đồng đã lưu, dùng khi render biên nhận hoặc phát hành lại. */
    public static ContractHeading of(LoanContract contract) {
        return new ContractHeading(
                contract.getContractNumber(), contract.getContractCode(), contract.getBorrowerIdentity());
    }

    /** Hợp đồng cũ in theo cách ghi trước V39. */
    public boolean legacy() {
        return borrower == null;
    }

    /** Số hợp đồng in trên văn bản: mã hiển thị với hợp đồng mới, mã {@code LC-} với hợp đồng cũ. */
    public String contractLabel() {
        return legacy() || contractCode == null ? contractNumber : contractCode;
    }

    /** Mã hồ sơ in trên văn bản, theo cùng quy tắc với {@link #contractLabel()}. */
    public String applicationLabel(LoanApplication application) {
        return legacy() || application.getApplicationCode() == null
                ? application.getApplicationNumber()
                : application.getApplicationCode();
    }
}
