package com.finora.loan.service.contract.impl;

import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.service.contract.ContractNumberGenerator;
import com.finora.loan.util.DisplayCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Sinh mã hợp đồng vay dạng {@code LC-} và 20 ký tự hex viết hoa, kèm mã hiển thị {@code HĐ-yyyyMMdd-XXXX}.
 *
 * <p>Mã tra cứu lấy 20 ký tự đầu của một UUID ngẫu nhiên đã bỏ dấu gạch. Mã hiển thị kiểm trùng trước và chọn lại vài
 * lần như mã hồ sơ.</p>
 */
@Component
@RequiredArgsConstructor
public class ContractNumberGeneratorImpl implements ContractNumberGenerator {

    static final int MAX_CODE_ATTEMPTS = 5;

    private final LoanContractRepository loanContractRepository;

    @Override
    public String next() {
        return "LC-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase(Locale.ROOT);
    }

    @Override
    public String nextCode(Instant createdAt) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = DisplayCodes.contract(createdAt);
            if (!loanContractRepository.existsByContractCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Không chọn được mã hiển thị hợp đồng chưa dùng sau "
                + MAX_CODE_ATTEMPTS + " lần thử");
    }
}
